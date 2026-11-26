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
import t3l.restaurant.t3l_restaurant.dto.CustomerRegistrationForm;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.security.CustomerSession;
import t3l.restaurant.t3l_restaurant.security.LoginReturnTarget;
import t3l.restaurant.t3l_restaurant.service.CustomerAccountService;
import t3l.restaurant.t3l_restaurant.service.CustomerRecovery;
import t3l.restaurant.t3l_restaurant.service.PhoneCustomerService;
import t3l.restaurant.t3l_restaurant.service.SmsOtpService.OtpException;
import t3l.restaurant.t3l_restaurant.service.SmsOtpService;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@Controller
public class CustomerLoginController {
  private final PhoneCustomerService phones;
  private final CustomerRecovery recovery;
  private final CustomerSession sessions;
  private final CustomerAccountService customers;
  private final AuthenticationManager passwordAuthenticationManager;
  private final PasswordEncoder passwords;
  public CustomerLoginController(
          PhoneCustomerService phones,
          CustomerRecovery recovery,
          CustomerSession sessions,
          CustomerAccountService customers,
          @Qualifier("customerPasswordAuthenticationManager")
          AuthenticationManager passwordAuthenticationManager,
          PasswordEncoder passwords
  ) {
    this.phones = phones;
    this.recovery = recovery;
    this.sessions = sessions;
    this.customers = customers;
    this.passwordAuthenticationManager = passwordAuthenticationManager;
    this.passwords = passwords;
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
      Model model) {
    String target = LoginReturnTarget.validate(returnTo);
    if (customers.signedInCustomer(authentication) != null) return "redirect:" + (target == null ? "/account" : target);
    return registrationPage(model, new CustomerRegistrationForm(), target);
  }

  @PostMapping("/register")
  String beginRegistration(
          @Valid @ModelAttribute("registrationForm") CustomerRegistrationForm form,
          BindingResult errors,
          @RequestParam(required = false) String returnTo,
          HttpServletRequest request,
          HttpServletResponse response,
          Model model
  ) {
    String target = LoginReturnTarget.validate(returnTo);
    String phone = SmsOtpService.normalizePhone(form.getPhone());

    if (!errors.hasErrors() && phone == null) {
      errors.rejectValue("phone", "phone.invalid",
              "Nhập số di động Việt Nam hợp lệ.");
    }

    if (!errors.hasErrors() && !phones.canRegister(phone)) {
      errors.rejectValue("phone", "phone.registered",
              "Số điện thoại đã có tài khoản hoặc hồ sơ khách. Vui lòng đăng nhập hoặc liên hệ nhà hàng.");
    }

    String rawPassword = form.getPassword() == null ? "" : form.getPassword();

    if (!errors.hasErrors() && !rawPassword.equals(form.getConfirmPassword())) {
      errors.rejectValue("confirmPassword", "password.mismatch",
              "Mật khẩu xác nhận không khớp.");
    }

    if (!errors.hasErrors()
            && rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
      errors.rejectValue("password", "password.tooLong",
              "Mật khẩu quá dài. Vui lòng dùng tối đa 72 byte UTF-8.");
    }

    if (errors.hasErrors()) {
      return registrationPage(model, form, target);
    }

    try {
      UserAccount customer = phones.registerPassword(form.getFullName(), phone, passwords.encode(rawPassword));
      return signedIn(request, response, customer, target);
    } catch (IllegalArgumentException exception) {
      model.addAttribute("registrationError", exception.getMessage());
    } catch (DataIntegrityViolationException exception) {
      model.addAttribute("registrationError", "Số điện thoại vừa được đăng ký. Vui lòng đăng nhập.");
    } catch (DataAccessException exception) {
      model.addAttribute("registrationError", "Chưa thể tạo tài khoản. Vui lòng thử lại.");
    }

    return registrationPage(model, form, target);
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

      UserAccount customer = customers.signedInCustomer(authentication);
      return signedIn(request, response, customer, target);
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
