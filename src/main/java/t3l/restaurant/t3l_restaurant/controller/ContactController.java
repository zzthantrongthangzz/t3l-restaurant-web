package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.dto.ContactForm;
import t3l.restaurant.t3l_restaurant.service.ContactService;

@Controller
public class ContactController {
  private final ContactService contacts;
  public ContactController(ContactService contacts) { this.contacts = contacts; }

  @InitBinder("contactForm")
  void trimFields(WebDataBinder binder) { binder.registerCustomEditor(String.class, new StringTrimmerEditor(false)); }

  @GetMapping("/about")
  public String about() { return "web/about"; }

  @GetMapping("/contact")
  public String contact(Model model, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    model.addAttribute("contactForm", new ContactForm());
    return "web/contact";
  }

  @PostMapping("/contact")
  public String submit(@Valid @ModelAttribute("contactForm") ContactForm form, BindingResult errors,
      Model model, RedirectAttributes redirect, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    if (!errors.hasErrors()) {
      try {
        contacts.submit(form);
        redirect.addFlashAttribute("contactNotice", "Cảm ơn bạn. T3L đã tiếp nhận lời nhắn và sẽ liên hệ qua thông tin bạn cung cấp.");
        return "redirect:/contact#contact-form";
      } catch (DataAccessException exception) {
        errors.reject("contact.unavailable", "Chưa thể lưu lời nhắn. Vui lòng thử lại hoặc liên hệ trực tiếp với T3L.");
      } catch (IllegalArgumentException exception) {
        errors.reject("contact.invalid", exception.getMessage());
      }
    }
    model.addAttribute("contactHasErrors", true);
    return "web/contact";
  }
}
