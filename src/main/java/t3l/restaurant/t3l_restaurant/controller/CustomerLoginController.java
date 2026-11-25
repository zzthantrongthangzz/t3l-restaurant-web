package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.Serializable;
import java.time.Instant;
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
import t3l.restaurant.t3l_restaurant.security.CustomerSession;
import t3l.restaurant.t3l_restaurant.security.LoginReturnTarget;
import t3l.restaurant.t3l_restaurant.service.CustomerAccountService;
import t3l.restaurant.t3l_restaurant.service.CustomerRecovery;
import t3l.restaurant.t3l_restaurant.service.PhoneCustomerService;
import t3l.restaurant.t3l_restaurant.service.SmsOtpService;
import t3l.restaurant.t3l_restaurant.service.SmsOtpService.OtpException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@Controller
public class CustomerLoginController {
  private static final String REGISTRATION_DRAFT = CustomerLoginController.class.getName() + ".REGISTRATION_DRAFT";
  private final SmsOtpService otp;
  private final PhoneCustomerService phones;
  private final CustomerRecovery recovery;
  private final CustomerSession sessions;
  private final CustomerAccountService customers;
  private final AuthenticationManager passwordAuthenticationManager;
  private final PasswordEncoder passwords;
  public CustomerLoginController(
          SmsOtpService otp,
          PhoneCustomerService phones,
          CustomerRecovery recovery,
          CustomerSession sessions,
          CustomerAccountService customers,
          @Qualifier("customerPasswordAuthenticationManager")
          AuthenticationManager passwordAuthenticationManager,
          PasswordEncoder passwords
  ) {
    this.otp = otp;
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
  String login(@RequestParam(required = false) String returnTo, HttpServletRequest request, Model model) {
    model.addAttribute("googleReturnTo", LoginReturnTarget.validate(returnTo));
    model.addAttribute("smsAvailable", otp.available(request));
    return "auth/login";
  }

  @GetMapping("/register")
  String registration(@RequestParam(required = false) String returnTo, Authentication authentication,
      HttpServletRequest request, Model model) {
    String target = LoginReturnTarget.validate(returnTo);
    if (customers.signedInCustomer(authentication) != null) return "redirect:" + (target == null ? "/account" : target);
    return registrationPage(request, model, registrationForm(registrationDraft(request)), target);
  }

  @PostMapping("/register")
  String beginRegistration(
          @Valid @ModelAttribute("registrationForm") CustomerRegistrationForm form,
          BindingResult errors,
          @RequestParam(required = false) String returnTo,
          HttpServletRequest request,
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
              "Số điện thoại đã có tài khoản. Vui lòng đăng nhập hoặc khôi phục tài khoản.");
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
      return registrationPage(request, model, form, target);
    }

    try {
      var issue = otp.issue(request, null, phone, null);

      var draft = new RegistrationDraft(
              form.getFullName(),
              issue.phone(),
              passwords.encode(rawPassword),
              target,
              Instant.now().plusSeconds(300)
      );

      request.getSession().setAttribute(REGISTRATION_DRAFT, draft);
      model.addAttribute("localCode", issue.localCode());
      model.addAttribute("registrationNotice", issue.localCode() == null
              ? "Mã xác thực đã được gửi đến số điện thoại của bạn."
              : "Chế độ kiểm thử cục bộ. Chưa gửi SMS.");
    } catch (OtpException exception) {
      model.addAttribute("registrationError", exception.getMessage());
    } catch (DataAccessException exception) {
      model.addAttribute("registrationError", "Chưa thể gửi mã xác thực. Vui lòng thử lại.");
    }

    return registrationPage(request, model, form, target);
  }

  @PostMapping("/register/verify")
  String completeRegistration(@RequestParam(defaultValue = "") String code, HttpServletRequest request,
      HttpServletResponse response, Model model) {
    RegistrationDraft draft = registrationDraft(request);
    CustomerRegistrationForm form = registrationForm(draft);
    if (draft == null) {
      model.addAttribute("registrationError", "Phiên đăng ký đã hết hạn. Vui lòng nhập lại thông tin.");
      return registrationPage(request, model, form, null);
    }
    try {
      var proof = otp.verify(request, null, code);
      if (!draft.phone().equals(proof.phone())) {
        throw new OtpException("Số điện thoại xác thực không khớp với thông tin đăng ký.");
      }
      UserAccount customer = phones.register(
              draft.fullName(),
              proof.phone(),
              proof.local(),
              draft.encodedPassword()
      );      request.getSession().removeAttribute(REGISTRATION_DRAFT);
      return signedIn(request, response, customer, draft.returnTo());
    } catch (OtpException exception) {
      model.addAttribute("registrationError", exception.getMessage());
    } catch (DataIntegrityViolationException exception) {
      model.addAttribute("registrationError", "Số điện thoại vừa được đăng ký. Vui lòng đăng nhập.");
    } catch (DataAccessException exception) {
      model.addAttribute("registrationError", "Chưa thể tạo tài khoản. Vui lòng thử lại.");
    }
    return registrationPage(request, model, form, draft.returnTo());
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
      model.addAttribute("smsAvailable", otp.available(request));
      return "auth/login";
    }
  }


  @GetMapping({"/login/sms", "/account/sms"})
  String sms(@RequestParam(required = false) String returnTo, Authentication authentication,
      HttpServletRequest request, Model model) {
    return smsPage(request, linkAccount(request, authentication), returnTo, model);
  }

  @PostMapping({"/login/sms/send", "/account/sms/send"})
  String send(@RequestParam(defaultValue = "") String phone, @RequestParam(required = false) String returnTo,
      Authentication authentication, HttpServletRequest request, Model model) {
    UserAccount account = linkAccount(request, authentication);
    Long accountId = account == null ? null : account.getId();
    try {
      var issue = otp.issue(request, accountId, phone, accountId == null ? returnTo : null);
      model.addAttribute("localCode", issue.localCode());
      model.addAttribute("requestAccepted", issue.localCode() == null);
    } catch (OtpException exception) {
      model.addAttribute("smsError", exception.getMessage());
    } catch (DataAccessException exception) {
      model.addAttribute("smsError", "Chưa thể xử lý yêu cầu. Vui lòng thử lại.");
    }
    return smsPage(request, account, returnTo, model);
  }

  @PostMapping({"/login/sms/verify", "/account/sms/verify"})
  String verify(@RequestParam(defaultValue = "") String code, @RequestParam(required = false) String returnTo,
      Authentication authentication, HttpServletRequest request, HttpServletResponse response, Model model,
      RedirectAttributes redirect) {
    UserAccount account = linkAccount(request, authentication);
    try {
      var proof = otp.verify(request, account == null ? null : account.getId(), code);
      if (account != null) {
        phones.link(account.getId(), proof.phone());
        redirect.addFlashAttribute("accountNotice", "Đã xác thực và cập nhật số điện thoại đăng nhập.");
        return "redirect:/account";
      }
      if (!proof.local() && phones.canRecover(proof.phone())) {
        recovery.begin(request.getSession(), proof.phone(), null, proof.returnTo());
        return "redirect:/login/recover";
      }
      UserAccount customer;
      try {
        customer = phones.signIn(proof.phone(), proof.local());
      } catch (DataIntegrityViolationException race) {
        customer = phones.signIn(proof.phone(), proof.local());
      }
      return signedIn(request, response, customer, proof.returnTo());
    } catch (OtpException exception) {
      model.addAttribute("smsError", exception.getMessage());
    } catch (DataAccessException exception) {
      model.addAttribute("smsError", "Chưa thể xác thực số điện thoại. Vui lòng yêu cầu mã mới.");
    }
    return smsPage(request, account, returnTo, model);
  }

  @GetMapping("/login/recover")
  String recovery(HttpServletRequest request, Model model) {
    return recoveryPage(request, model);
  }

  @PostMapping({"/login/recover", "/login/recover/new"})
  String recover(@RequestParam(defaultValue = "") String username, @RequestParam(defaultValue = "") String password,
      HttpServletRequest request, HttpServletResponse response, Model model, RedirectAttributes redirect) {
    try {
      var recovered = recovery.complete(request, username, password, request.getRequestURI().endsWith("/new"));
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

  private UserAccount linkAccount(HttpServletRequest request, Authentication authentication) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return path.startsWith("/account/") ? customers.signedInCustomer(authentication) : null;
  }

  private String recoveryPage(HttpServletRequest request, Model model) {
    var pending = recovery.pending(request.getSession(false));
    model.addAttribute("recoveryPending", pending != null);
    model.addAttribute("canCreateNew", pending != null && pending.phone() != null);
    return "auth/recover";
  }

  private String registrationPage(HttpServletRequest request, Model model, CustomerRegistrationForm form, String target) {
    RegistrationDraft draft = registrationDraft(request);
    String pendingPhone = otp.pendingPhone(request.getSession(false), null);
    boolean pending = draft != null && draft.phone().equals(pendingPhone);
    model.addAttribute("registrationForm", form);
    model.addAttribute("registrationPending", pending);
    model.addAttribute("registrationPhone", pending ? draft.phone() : null);
    model.addAttribute("registrationName", pending ? draft.fullName() : null);
    model.addAttribute("registrationReturnTo", target != null ? target : pending ? draft.returnTo() : null);
    model.addAttribute("smsAvailable", otp.available(request));
    return "auth/register";
  }

  private CustomerRegistrationForm registrationForm(RegistrationDraft draft) {
    var form = new CustomerRegistrationForm();
    if (draft != null) {
      form.setFullName(draft.fullName());
      form.setPhone(draft.phone());
    }
    return form;
  }

  private RegistrationDraft registrationDraft(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session == null) return null;
    Object value = session.getAttribute(REGISTRATION_DRAFT);
    if (!(value instanceof RegistrationDraft draft)) return null;
    if (draft.expiresAt().isBefore(Instant.now())) {
      session.removeAttribute(REGISTRATION_DRAFT);
      return null;
    }
    return draft;
  }

  private String smsPage(HttpServletRequest request, UserAccount account, String returnTo, Model model) {
    Long accountId = account == null ? null : account.getId();
    model.addAttribute("linkingSms", accountId != null);
    model.addAttribute("smsAvailable", account == null ? otp.available(request)
        : otp.canLinkPhone() && !PhoneCustomerService.isLocal(account));
    model.addAttribute("smsReturnTo", LoginReturnTarget.validate(returnTo));
    model.addAttribute("pendingPhone", otp.pendingPhone(request.getSession(false), accountId));
    return "auth/sms";
  }

  private record RegistrationDraft(
          String fullName,
          String phone,
          String encodedPassword,
          String returnTo,
          Instant expiresAt
  ) implements Serializable {}}
