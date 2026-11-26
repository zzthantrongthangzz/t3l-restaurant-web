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
  private final t3l.restaurant.t3l_restaurant.service.VoucherService vouchers;

  @ModelAttribute
  void privateResponse(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
  }

  @GetMapping
  String preorder(@PathVariable String code, Authentication authentication, Model model, RedirectAttributes redirect) {
    var booking = ownedBooking(code, authentication);
    var blocked = management.preorderBlockedReason(booking);
    if (blocked != null) return bookingDetails(booking, blocked, redirect);
    return render(booking, null, model);
  }

  @PostMapping
  String requestChange(@PathVariable String code, @Valid @ModelAttribute("preorderForm") PreorderForm form,
      BindingResult errors, Authentication authentication, Model model, RedirectAttributes redirect) {
    var booking = ownedBooking(code, authentication);
    var blocked = management.preorderBlockedReason(booking);
    if (blocked != null) return bookingDetails(booking, blocked, redirect);
    if (!errors.hasErrors()) {
      try {
        management.requestPreorder(code, booking.getCustomerId(), form);
        return bookingDetails(booking, "Đã gửi yêu cầu đổi món. Giỏ hiện tại được giữ đến khi nhân viên duyệt.", redirect);
      } catch (BookingException exception) {
        errors.reject("preorder.invalid", exception.getMessage());
      } catch (TransientDataAccessException exception) {
        errors.reject("preorder.busy", "Yêu cầu đang được xử lý ở phiên khác. Vui lòng thử lại.");
      }
    }
    return render(booking, form, model);
  }

  private String bookingDetails(Reservation booking, String notice, RedirectAttributes redirect) {
    redirect.addAttribute("code", booking.getCode());
    redirect.addFlashAttribute("lookupNotice", notice);
    return "redirect:/reservations/lookup";
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
      form.setVoucherCode(current.voucherCode());
      model.addAttribute("preorderForm", form);
    }
    model.addAttribute("booking", booking);
    model.addAttribute("voucherOffers",vouchers.offers(booking.getCustomerId(),false));
    model.addAttribute("selectedVoucher",form.getVoucherCode());
    model.addAttribute("voucherBookingCode",booking.getCode());
    model.addAttribute("minimumDeposit", bookings.minimumDeposit(booking.getTable()));
    model.addAttribute("preorderCards", preorders.picker(form, order));
    return "account/preorder";
  }
}
