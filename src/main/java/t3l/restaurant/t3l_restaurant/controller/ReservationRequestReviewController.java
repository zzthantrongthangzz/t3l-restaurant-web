package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.service.ReservationManagementService;

@Controller
public class ReservationRequestReviewController {
  private final ReservationManagementService management;
  public ReservationRequestReviewController(ReservationManagementService management) { this.management = management; }

  @PostMapping({"/staff/reservations/requests/{id}/review", "/admin/reservations/requests/{id}/review"})
  public String review(@PathVariable Long id, @RequestParam String decision,
      @RequestParam(required = false) String reviewNote, Authentication authentication, RedirectAttributes redirect,
      HttpServletRequest request) {
    try {
      management.review(id, decision, reviewNote, authentication.getName());
      redirect.addFlashAttribute("notice", "Đã xử lý yêu cầu và cập nhật thông tin cho khách.");
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    } catch (TransientDataAccessException exception) {
      redirect.addFlashAttribute("error", "Yêu cầu đang được xử lý ở phiên khác. Vui lòng tải lại và thử lại.");
    }
    boolean admin = request.getRequestURI().substring(request.getContextPath().length()).startsWith("/admin/");
    return admin ? "redirect:/admin/reservations" : "redirect:/staff/reservations";
  }
}
