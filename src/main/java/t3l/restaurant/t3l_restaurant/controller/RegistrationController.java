package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.dto.RegistrationForm;
import t3l.restaurant.t3l_restaurant.service.RegistrationService;
import t3l.restaurant.t3l_restaurant.service.RegistrationService.DuplicateAccountException;

@Controller
public class RegistrationController {
  private final RegistrationService registration;

  public RegistrationController(RegistrationService registration) { this.registration = registration; }

  @InitBinder("registrationForm")
  void registrationFields(WebDataBinder binder) {
    binder.setAllowedFields("fullName", "email", "phone", "username", "password", "confirmPassword");
  }

  @GetMapping("/register")
  public String register(Model model, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    model.addAttribute("registrationForm", new RegistrationForm());
    return "auth/register";
  }

  @PostMapping("/register")
  public String submit(@Valid @ModelAttribute("registrationForm") RegistrationForm form, BindingResult errors,
      RedirectAttributes redirect, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    if (!errors.hasErrors()) {
      try {
        registration.register(form);
        redirect.addFlashAttribute("registrationNotice", "Đăng ký thành công. Bạn có thể đăng nhập bằng tài khoản vừa tạo.");
        return "redirect:/login";
      } catch (DuplicateAccountException exception) {
        errors.rejectValue(exception.getField(), "register.duplicate", exception.getMessage());
      } catch (DataIntegrityViolationException exception) {
        // The unique indexes also protect simultaneous requests, after the service transaction rolls back.
        errors.reject("register.duplicate", "Tên đăng nhập hoặc email đã được sử dụng. Vui lòng kiểm tra lại.");
      } catch (DataAccessException exception) {
        errors.reject("register.unavailable", "Chưa thể tạo tài khoản. Vui lòng thử lại sau.");
      }
    }
    return "auth/register";
  }
}
