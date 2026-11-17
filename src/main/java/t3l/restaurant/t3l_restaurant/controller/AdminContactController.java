package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.service.ContactService;

@Controller
@RequestMapping("/admin/contacts")
public class AdminContactController {
  private final ContactService contacts;
  public AdminContactController(ContactService contacts) { this.contacts = contacts; }

  @ModelAttribute
  void privateResponse(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
  }

  @GetMapping
  public String list(@RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "0") int page, Model model) {
    try { model.addAttribute("contacts", contacts.list(status, page)); }
    catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
    model.addAttribute("filterStatus", status);
    return "admin/contacts";
  }

  @GetMapping("/{id}")
  public String detail(@PathVariable Long id, Model model) {
    try { model.addAttribute("contact", contacts.find(id)); }
    catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    return "admin/contact-detail";
  }

  @PostMapping("/{id}/status")
  public String update(@PathVariable Long id, @RequestParam long version, @RequestParam String status,
      @RequestParam(defaultValue = "") String adminNote, Authentication authentication, RedirectAttributes redirect) {
    try {
      contacts.update(id, version, status, adminNote, authentication.getName());
      redirect.addFlashAttribute("notice", "Đã cập nhật liên hệ.");
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    } catch (OptimisticLockingFailureException exception) {
      redirect.addFlashAttribute("error", "Liên hệ vừa được Admin khác cập nhật. Vui lòng kiểm tra lại.");
    }
    return "redirect:/admin/contacts/" + id;
  }
}
