package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.security.LoginReturnTarget;
import t3l.restaurant.t3l_restaurant.service.PasswordResetService;
import t3l.restaurant.t3l_restaurant.service.PasswordResetService.ResetException;
import t3l.restaurant.t3l_restaurant.service.PasswordResetService.Step;

@Controller
@RequestMapping("/login/forgot-password")
@RequiredArgsConstructor
public class PasswordResetController {
  private static final String RESET_PAGE = "redirect:/login/forgot-password";
  private static final String TEMPORARY_ERROR = "Chưa thể xử lý yêu cầu. Vui lòng thử lại sau.";
  private final PasswordResetService passwords;

  @ModelAttribute
  void privateResponse(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
  }

  @GetMapping
  String page(@RequestParam(required = false) String returnTo, @RequestParam(defaultValue = "false") boolean start,
      HttpServletRequest request, Model model) {
    var view = passwords.view(request.getSession(false));
    String target = view.target() == null ? LoginReturnTarget.validate(returnTo) : view.target();
    model.addAttribute("resetStep", start ? Step.PHONE : view.step());
    model.addAttribute("maskedResetEmail", start ? null : view.maskedEmail());
    model.addAttribute("resetReturnTo", target);
    return "auth/password-reset";
  }

  @PostMapping("/request")
  String requestCode(@RequestParam(required = false) String phone, @RequestParam(required = false) String returnTo,
      HttpServletRequest request, RedirectAttributes redirect) {
    try {
      if (phone == null) {
        passwords.resend(request);
      } else {
        passwords.start(request, phone, returnTo);
      }
    } catch (ResetException exception) {
      redirect.addFlashAttribute("resetError", exception.getMessage());
    } catch (DataAccessException exception) {
      redirect.addFlashAttribute("resetError", TEMPORARY_ERROR);
    }
    if (phone != null && redirect.getFlashAttributes().containsKey("resetError")) {
      redirect.addAttribute("start", true);
      addTarget(redirect, LoginReturnTarget.validate(returnTo));
    }
    return RESET_PAGE;
  }

  @PostMapping("/verify")
  String verify(@RequestParam(defaultValue = "") String code, HttpServletRequest request, RedirectAttributes redirect) {
    try {
      passwords.verify(request, code.strip());
    } catch (ResetException exception) {
      redirect.addFlashAttribute("resetError", exception.getMessage());
    }
    return RESET_PAGE;
  }

  @PostMapping("/reset")
  String reset(@RequestParam(defaultValue = "") String password, @RequestParam(defaultValue = "") String confirmPassword,
      HttpServletRequest request, HttpServletResponse response, Authentication authentication, RedirectAttributes redirect) {
    try {
      var completed = passwords.reset(request, password, confirmPassword);
      if (authentication != null && completed.username().equals(authentication.getName())) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
      }
      addTarget(redirect, completed.target());
      redirect.addFlashAttribute("passwordResetSuccess", true);
      return "redirect:/login";
    } catch (ResetException exception) {
      redirect.addFlashAttribute("resetError", exception.getMessage());
    } catch (DataAccessException exception) {
      redirect.addFlashAttribute("resetError", TEMPORARY_ERROR);
    }
    return RESET_PAGE;
  }

  private void addTarget(RedirectAttributes redirect, String target) {
    if (target != null) redirect.addAttribute("returnTo", target);
  }
}
