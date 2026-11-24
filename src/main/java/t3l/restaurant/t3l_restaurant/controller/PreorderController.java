package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.dto.PreorderForm;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.service.BookingException;
import t3l.restaurant.t3l_restaurant.service.CustomerAccountService;
import t3l.restaurant.t3l_restaurant.service.PreorderService;
import t3l.restaurant.t3l_restaurant.service.ReservationManagementService;
import t3l.restaurant.t3l_restaurant.service.ReservationService;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
@RequestMapping("/account/reservations/{code}/preorder")
public class PreorderController {
  private final CustomerAccountService customers;
  private final ReservationService bookings;
  private final ReservationManagementService management;
  private final PreorderService preorders;

  @ModelAttribute
  void privateResponse(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
  }

  @GetMapping
  String preorder(@PathVariable String code, Authentication authentication, Model model) {
    var booking = ownedBooking(code, authentication);
    return render(booking, null, model);
  }

  @PostMapping
  String requestChange(@PathVariable String code, @Valid @ModelAttribute("preorderForm") PreorderForm form,
      BindingResult errors, Authentication authentication, Model model, RedirectAttributes redirect) {
    var booking = ownedBooking(code, authentication);
    if (!errors.hasErrors()) {
      try {
        management.requestPreorder(code, booking.getCustomerId(), form);
        redirect.addAttribute("code", code);
        redirect.addFlashAttribute("preorderNotice", "Đã gửi yêu cầu đổi món. Giỏ hiện tại được giữ đến khi nhân viên duyệt.");
        return "redirect:/account/reservations/{code}/preorder";
      } catch (BookingException exception) {
        errors.reject("preorder.invalid", exception.getMessage());
      } catch (TransientDataAccessException exception) {
        errors.reject("preorder.busy", "Yêu cầu đang được xử lý ở phiên khác. Vui lòng thử lại.");
      }
    }
    return render(booking, form, model);
  }

  private Reservation ownedBooking(String code, Authentication authentication) {
    var booking = bookings.findVisibleByCode(code, customers.signedInCustomer(authentication), false);
    if (booking == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return booking;
  }

  private String render(Reservation booking, PreorderForm form, Model model) {
    var order = preorders.order(booking.getId());
    var current = preorders.current(order);
    if (form == null) {
      form = new PreorderForm();
      form.setPreorderQuantities(current.quantities());
      form.setPreorderNote(current.note());
      model.addAttribute("preorderForm", form);
    }
    var blocked = management.preorderBlockedReason(booking);
    if (booking.getTable() == null) blocked = "Chỉ chọn món khi đặt bàn đã có bàn được phân.";
    model.addAttribute("booking", booking);
    model.addAttribute("currentPreorder", current);
    if (order != null && order.isLegacyCustomerOrder()) model.addAttribute("legacyOrder", order);
    model.addAttribute("preorderHistory", management.history(booking.getId()).stream()
        .filter(request -> "PREORDER".equals(request.getRequestType())).toList());
    model.addAttribute("preorderBlocked", blocked);
    if (blocked == null) {
      model.addAttribute("minimumDeposit", bookings.minimumDeposit(booking.getTable()));
      model.addAttribute("preorderCards", preorders.picker(form, order));
    }
    return "account/preorder";
  }
}
