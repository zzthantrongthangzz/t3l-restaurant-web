package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.dto.FaqForm;
import t3l.restaurant.t3l_restaurant.service.AdminFaqService;
import t3l.restaurant.t3l_restaurant.service.BookingException;

@Controller
@RequestMapping("/admin/faqs")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminFaqController {
  private static final String LIST = "redirect:/admin/faqs";
  private final AdminFaqService faqs;

  @GetMapping
  public String list(Model model) {
    model.addAttribute("faqs", faqs.list());
    return "admin/faqs";
  }

  @GetMapping({"/new", "/{id}/edit"})
  public String edit(@PathVariable(required = false) String id, Model model) {
    model.addAttribute("form", id == null ? new FaqForm() : faqs.form(id));
    return editor(id, model);
  }

  @PostMapping({"", "/{id}"})
  public String save(@PathVariable(required = false) String id, @Valid @ModelAttribute("form") FaqForm form,
      BindingResult errors, Model model, RedirectAttributes redirect) {
    if (errors.hasErrors()) return editor(id, model);
    faqs.save(id, form);
    redirect.addFlashAttribute("notice", "Đã lưu câu hỏi.");
    return LIST;
  }

  @PostMapping("/{id}/status")
  public String status(@PathVariable String id, @RequestParam boolean published, RedirectAttributes redirect) {
    faqs.setPublished(id, published);
    redirect.addFlashAttribute("notice", published ? "Đã hiện câu hỏi." : "Đã ẩn câu hỏi.");
    return LIST;
  }

  @ExceptionHandler(BookingException.class)
  public String missing(BookingException error, HttpServletResponse response, Model model) {
    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
    model.addAttribute("message", error.getMessage());
    return "admin/invalid";
  }

  private String editor(String id, Model model) {
    model.addAttribute("faqId", id);
    return "admin/faq-form";
  }
}
