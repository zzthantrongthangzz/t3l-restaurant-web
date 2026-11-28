package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.dto.CustomerRegistrationForm;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.security.AccountPrincipal;
import t3l.restaurant.t3l_restaurant.security.CustomerSession;
import t3l.restaurant.t3l_restaurant.security.LoginReturnTarget;
import t3l.restaurant.t3l_restaurant.service.CustomerAccountService;
import t3l.restaurant.t3l_restaurant.service.CustomerEmailVerificationService;
import t3l.restaurant.t3l_restaurant.service.EmailOtpService.VerificationException;
import t3l.restaurant.t3l_restaurant.service.CustomerRecovery;
import t3l.restaurant.t3l_restaurant.service.PhoneCustomerService;
import t3l.restaurant.t3l_restaurant.service.SmsOtpService.OtpException;
import t3l.restaurant.t3l_restaurant.service.SmsOtpService;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;

@Controller
public class CustomerLoginController {
  private static final String REGISTER = "redirect:/register";
  private final PhoneCustomerService phones;
  private final CustomerRecovery recovery;
  private final CustomerSession sessions;
  private final CustomerAccountService customers;
  private final AuthenticationManager passwordAuthenticationManager;
  private final CustomerEmailVerificationService emails;
  public CustomerLoginController(
          PhoneCustomerService phones,
          CustomerRecovery recovery,
          CustomerSession sessions,
          CustomerAccountService customers,
          @Qualifier("customerPasswordAuthenticationManager")
          AuthenticationManager passwordAuthenticationManager,
          CustomerEmailVerificationService emails
  ) {
    this.phones = phones;
    this.recovery = recovery;
    this.sessions = sessions;
    this.customers = customers;
    this.passwordAuthenticationManager = passwordAuthenticationManager;
    this.emails = emails;
  }

  @ModelAttribute
  void privateResponse(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
  }

  @GetMapping("/login")
  String login(@RequestParam(required = false) String returnTo, Model model) {
    model.addAttribute("googleReturnTo", LoginReturnTarget.validate(returnTo));
    return "auth/login";
  }

  @GetMapping("/register")
  String registration(@RequestParam(required = false) String returnTo, Authentication authentication,
      HttpServletRequest request, Model model) {
    String target = LoginReturnTarget.validate(returnTo);
    if (customers.signedInCustomer(authentication) != null) return "redirect:" + (target == null ? "/account" : target);
    var maskedEmail = emails.registrationEmail(request.getSession(false));
    if (maskedEmail != null) {
      model.addAttribute("registrationEmail", maskedEmail);
      model.addAttribute("registrationReturnTo", emails.registrationTarget(request.getSession(false)));
      return "auth/register";
    }
    return registrationPage(model, new CustomerRegistrationForm(), target);
  }

  @PostMapping("/register")
  String beginRegistration(
          @Valid @ModelAttribute("registrationForm") CustomerRegistrationForm form,
          BindingResult errors,
          @RequestParam(required = false) String returnTo,
          HttpServletRequest request,
          Authentication authentication,
          RedirectAttributes redirect,
          Model model
  ) {
    String target = LoginReturnTarget.validate(returnTo);
    if (customers.signedInCustomer(authentication) != null) return "redirect:" + (target == null ? "/account" : target);
    String phone = SmsOtpService.normalizePhone(form.getPhone());
    String email = CustomerEmailVerificationService.normalizeEmail(form.getEmail());

    if (!errors.hasFieldErrors("phone") && phone == null) {
      errors.rejectValue("phone", "phone.invalid",
              "Nhập số di động Việt Nam hợp lệ.");
    }

    if (!errors.hasFieldErrors("email") && email == null) {
      errors.rejectValue("email", "email.invalid", "Vui lòng nhập email hợp lệ.");
    }

    if (!errors.hasErrors() && !phones.canRegister(phone)) {
      errors.rejectValue("phone", "phone.registered",
              "Số điện thoại đã có tài khoản hoặc hồ sơ khách. Vui lòng đăng nhập hoặc liên hệ nhà hàng.");
    }

    String rawPassword = form.getPassword() == null ? "" : form.getPassword();

    if (!errors.hasFieldErrors("confirmPassword") && !rawPassword.equals(form.getConfirmPassword())) {
      errors.rejectValue("confirmPassword", "password.mismatch",
              "Mật khẩu xác nhận không khớp.");
    }

    if (!errors.hasFieldErrors("password")
            && rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
      errors.rejectValue("password", "password.tooLong",
              "Mật khẩu quá dài. Vui lòng dùng tối đa 72 byte UTF-8.");
    }

    if (errors.hasErrors()) {
      return registrationPage(model, form, target);
    }

    try {
      if (emails.emailInUse(email, null)) {
        errors.rejectValue("email", "email.duplicate", "Email đã được sử dụng cho tài khoản khác.");
        return registrationPage(model, form, target);
      }
      emails.startRegistration(request, form.getFullName(), phone, email, rawPassword, target);
      return REGISTER;
    } catch (VerificationException exception) {
      model.addAttribute("registrationError", exception.getMessage());
    } catch (DataAccessException exception) {
      model.addAttribute("registrationError", "Chưa thể gửi mã xác minh. Vui lòng thử lại.");
    }

    return registrationPage(model, form, target);
  }

  @PostMapping("/register/verify")
  String verifyRegistration(@RequestParam(defaultValue = "") String code, Authentication authentication,
      HttpServletRequest request, HttpServletResponse response, RedirectAttributes redirect) {
    if (customers.signedInCustomer(authentication) != null) return "redirect:/account";
    String target = emails.registrationTarget(request.getSession(false));
    try {
      var registered = emails.verifyRegistration(request, code);
      return signedIn(request, response, registered.account(), registered.target());
    } catch (VerificationException | IllegalArgumentException exception) {
      redirect.addFlashAttribute("registrationError", exception.getMessage());
    } catch (DataIntegrityViolationException exception) {
      redirect.addFlashAttribute("registrationError", "Email hoặc số điện thoại vừa được đăng ký. Vui lòng kiểm tra lại.");
    } catch (DataAccessException exception) {
      redirect.addFlashAttribute("registrationError", "Chưa thể tạo tài khoản. Vui lòng thử lại.");
    }
    if (target != null && emails.registrationEmail(request.getSession(false)) == null) redirect.addAttribute("returnTo", target);
    return REGISTER;
  }

  @PostMapping("/register/resend")
  String resendRegistration(Authentication authentication, HttpServletRequest request, RedirectAttributes redirect) {
    if (customers.signedInCustomer(authentication) != null) return "redirect:/account";
    try {
      emails.resendRegistration(request);
      redirect.addFlashAttribute("registrationNotice", "Đã yêu cầu gửi mã mới.");
    } catch (VerificationException exception) {
      redirect.addFlashAttribute("registrationError", exception.getMessage());
    } catch (DataAccessException exception) {
      redirect.addFlashAttribute("registrationError", "Chưa thể gửi mã xác minh. Vui lòng thử lại.");
    }
    return REGISTER;
  }

  @PostMapping("/register/cancel")
  String cancelRegistration(HttpServletRequest request, RedirectAttributes redirect) {
    var target = emails.cancelRegistration(request.getSession(false));
    if (target != null) redirect.addAttribute("returnTo", target);
    return REGISTER;
  }

  @PostMapping("/login/password")
  String passwordLogin(
          @RequestParam(defaultValue = "") String phone,
          @RequestParam(defaultValue = "") String password,
          @RequestParam(required = false) String returnTo,
          HttpServletRequest request,
          HttpServletResponse response,
          Model model
  ) {
    String target = LoginReturnTarget.validate(returnTo);

    try {
      var authentication = passwordAuthenticationManager.authenticate(
              new UsernamePasswordAuthenticationToken(phone, password));

      recovery.clear(request.getSession(false));
      emails.cancelRegistration(request.getSession(false));
      sessions.signIn(request, response, (AccountPrincipal) authentication.getPrincipal());
      return "redirect:" + (target == null ? "/" : target);
    } catch (AuthenticationException exception) {
      model.addAttribute("passwordError", "Số điện thoại hoặc mật khẩu không đúng.");
      model.addAttribute("googleReturnTo", target);
      return "auth/login";
    }
  }


  @GetMapping("/login/recover")
  String recovery(HttpServletRequest request, Model model) {
    return recoveryPage(request, model);
  }

  @PostMapping("/login/recover")
  String recover(@RequestParam(defaultValue = "") String username, @RequestParam(defaultValue = "") String password,
      HttpServletRequest request, HttpServletResponse response, Model model) {
    try {
      var recovered = recovery.complete(request, username, password);
      return signedIn(request, response, recovered.account(), recovered.target());
    } catch (OtpException exception) {
      model.addAttribute("recoveryError", exception.getMessage());
    } catch (DataAccessException exception) {
      model.addAttribute("recoveryError", "Chưa thể chuyển tài khoản. Vui lòng thử lại.");
    }
    return recoveryPage(request, model);
  }

  private String signedIn(HttpServletRequest request, HttpServletResponse response, UserAccount customer, String returnTo) {
    recovery.clear(request.getSession(false));
    sessions.signIn(request, response, customer);
    String target = LoginReturnTarget.validate(returnTo);
    return "redirect:" + (target == null ? "/" : target);
  }

  private String recoveryPage(HttpServletRequest request, Model model) {
    var pending = recovery.pending(request.getSession(false));
    model.addAttribute("recoveryPending", pending != null);
    return "auth/recover";
  }

  private String registrationPage(Model model, CustomerRegistrationForm form, String target) {
    model.addAttribute("registrationForm", form);
    model.addAttribute("registrationReturnTo", target);
    return "auth/register";
  }
}
