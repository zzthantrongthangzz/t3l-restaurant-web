package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.dto.*;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.TableKind;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.RestaurantTableRepository;
import t3l.restaurant.t3l_restaurant.repository.DiningOrderRepository;
import t3l.restaurant.t3l_restaurant.service.*;

@Controller
@RequestMapping("/reservations/lookup")
public class ReservationLookupController {
  private final ReservationManagementService management;
  private final ReservationService bookings;
  private final ReservationLookupAccess access;
  private final ReservationConfirmationAccess confirmationAccess;
  private final ReservationChangePolicy policy;
  private final ReservationSchedule schedule;
  private final RestaurantTableRepository tables;
  private final CustomerAccountService customers;
  private final PaymentIntentService paymentIntents;
  private final SepayProperties sepay;
  private final DiningOrderRepository diningOrders;
  private final TrackingService tracking;

  public ReservationLookupController(ReservationManagementService management, ReservationService bookings,
      ReservationLookupAccess access, ReservationConfirmationAccess confirmationAccess,
      ReservationChangePolicy policy, ReservationSchedule schedule, RestaurantTableRepository tables,
      CustomerAccountService customers, PaymentIntentService paymentIntents, SepayProperties sepay,
      DiningOrderRepository diningOrders, TrackingService tracking) {
    this.management = management;
    this.bookings = bookings;
    this.access = access;
    this.confirmationAccess = confirmationAccess;
    this.policy = policy;
    this.schedule = schedule;
    this.tables = tables;
    this.customers = customers;
    this.paymentIntents = paymentIntents;
    this.sepay = sepay;
    this.diningOrders = diningOrders;
    this.tracking = tracking;
  }

  @ModelAttribute
  void privateResponse(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store, max-age=0");
    response.setHeader("Pragma", "no-cache");
    response.setHeader("Referrer-Policy", "no-referrer");
  }

  @GetMapping
  public String lookup(@RequestParam(required = false) String code, HttpServletRequest request,
      Authentication authentication, Model model) {
    var form = new ReservationLookupForm();
    model.addAttribute("lookupForm", form);
    var customer = customers.signedInCustomer(authentication);
    Reservation booking = visibleBooking(code, request, customer);
    if (booking != null) {
      if (addDetails(model, booking, customer) && !booking.isDepositDemo()
          && !"checkout".equals(request.getHeader("X-T3L-Booking-Refresh"))) {
        tracking.depositOpened(request, authentication, booking.getId());
      }
    } else if (access.canView(request.getSession(false), code)) {
      model.addAttribute("lookupError", "Không thể hiển thị đặt bàn này. Vui lòng tra cứu lại.");
    }
    return "web/reservation-lookup";
  }

  @PostMapping
  public String searchByPhone(@Valid @ModelAttribute("lookupForm") ReservationLookupForm form,
      BindingResult errors, HttpSession session, Model model) {
    if (errors.hasErrors()) return "web/reservation-lookup";

    var matches = management.lookupByPhone(form);
    if (matches.isEmpty()) {
      model.addAttribute("lookupError", "Không tìm thấy đặt bàn nào khớp số điện thoại này.");
    } else {
      for (Reservation booking : matches) {
        access.grant(session, booking.getCode());
        confirmationAccess.grant(session, booking.getCode());
      }
      model.addAttribute("lookupResults", matches);
    }
    return "web/reservation-lookup";
  }

  @PostMapping("/{code}/change")
  public String change(@PathVariable String code, @Valid @ModelAttribute("changeForm") ReservationUpdateForm form,
      BindingResult errors, HttpServletRequest request, Authentication authentication, Model model,
      RedirectAttributes redirect) {
    var customer = customers.signedInCustomer(authentication);
    Reservation booking = visibleBooking(code, request, customer);
    if (booking == null) return requireVerification(redirect);
    if (!errors.hasErrors()) {
      try {
        management.requestChange(code, form, booking.getCustomerId());
        return submitted(code, redirect);
      } catch (BookingException exception) {
        if (exception.getField() == null) errors.reject("change.invalid", exception.getMessage());
        else errors.rejectValue(exception.getField(), "change.invalid", exception.getMessage());
      } catch (TransientDataAccessException exception) {
        errors.reject("change.busy", "Hệ thống đang xử lý yêu cầu khác. Vui lòng tải lại và thử lại.");
      }
    }
    model.addAttribute("changeOpen", true);
    return redisplay(code, request, customer, model, redirect);
  }

  @PostMapping("/{code}/cancel")
  public String cancel(@PathVariable String code, @Valid @ModelAttribute("cancelForm") ReservationCancellationForm form,
      BindingResult errors, HttpServletRequest request, Authentication authentication, Model model,
      RedirectAttributes redirect) {
    var customer = customers.signedInCustomer(authentication);
    Reservation booking = visibleBooking(code, request, customer);
    if (booking == null) return requireVerification(redirect);
    if (!errors.hasErrors()) {
      try {
        management.requestCancellation(code, form, booking.getCustomerId());
        return submitted(code, redirect);
      } catch (BookingException exception) {
        errors.reject("cancel.invalid", exception.getMessage());
      } catch (TransientDataAccessException exception) {
        errors.reject("cancel.busy", "Hệ thống đang xử lý yêu cầu khác. Vui lòng tải lại và thử lại.");
      }
    }
    model.addAttribute("cancelOpen", true);
    return redisplay(code, request, customer, model, redirect);
  }

  @PostMapping("/{code}/claim")
  public String claim(@PathVariable String code, HttpServletRequest request, Authentication authentication,
      RedirectAttributes redirect) {
    var customer = customers.signedInCustomer(authentication);
    if (!access.canView(request.getSession(false), code)) return requireVerification(redirect);
    try {
      var booking = management.claim(code, customer);
      access.revoke(request.getSession(false), code);
      if (bookings.isPast(booking)) redirect.addAttribute("tab", "past");
      redirect.addFlashAttribute("accountNotice", "Đã thêm đặt bàn vào tài khoản của bạn.");
      return "redirect:/account/reservations";
    } catch (BookingException | TransientDataAccessException exception) {
      redirect.addFlashAttribute("lookupError", "Không thể thêm đặt bàn lúc này. Vui lòng tra cứu lại.");
      return "redirect:/reservations/lookup";
    }
  }

  private String submitted(String code, RedirectAttributes redirect) {
    redirect.addAttribute("code", code);
    redirect.addFlashAttribute("lookupNotice", "Đã tiếp nhận yêu cầu. Lịch đặt bàn hiện tại được giữ nguyên cho đến khi nhân viên duyệt.");
    return "redirect:/reservations/lookup";
  }

  private String requireVerification(RedirectAttributes redirect) {
    redirect.addFlashAttribute("lookupError", "Phiên tra cứu đã hết hạn. Vui lòng nhập lại số điện thoại đã đặt bàn.");
    return "redirect:/reservations/lookup";
  }

  private Reservation visibleBooking(String code, HttpServletRequest request, UserAccount customer) {
    return bookings.findVisibleByCode(code, customer, access.canView(request.getSession(false), code));
  }

  private String redisplay(String code, HttpServletRequest request, UserAccount customer, Model model,
      RedirectAttributes redirect) {
    Reservation booking = visibleBooking(code, request, customer);
    if (booking == null) return requireVerification(redirect);
    var lookup = new ReservationLookupForm();
    model.addAttribute("lookupForm", lookup);
    addDetails(model, booking, customer);
    return "web/reservation-lookup";
  }

  private boolean addDetails(Model model, Reservation booking, UserAccount customer) {
    model.addAttribute("reservation", booking);
    var order = diningOrders.findByReservation_Id(booking.getId()).orElse(null);
    var deposit = paymentIntents.customerDeposit(booking);
    model.addAttribute("preorder", order);
    model.addAttribute("depositRefundTerms", bookings.depositRefundTerms());
    model.addAttribute("depositCheckout", deposit.checkout());
    model.addAttribute("paymentStatus", deposit.status());
    model.addAttribute("qrPayments", paymentIntents.usesQr());
    model.addAttribute("demoEnabled", sepay.demoAllowed());
    boolean owned = bookings.owns(booking, customer);
    model.addAttribute("ownedBooking", owned);
    model.addAttribute("pastBooking", bookings.isPast(booking));
    model.addAttribute("canClaimBooking", customer != null && booking.getCustomerId() == null);
    model.addAttribute("history", management.history(booking.getId()));
    var blocked = management.requestBlockedReason(booking);
    model.addAttribute("blockedReason", blocked);
    model.addAttribute("canEditPreorder", owned && blocked == null && management.preorderBlockedReason(booking) == null);
    model.addAttribute("canAddMenuItems", owned && bookings.canAddMenuItems(booking, order, false));
    model.addAttribute("deadline", policy.deadline(booking));
    model.addAttribute("changeNoticeMinutes", policy.noticeMinutes());
    if (!model.containsAttribute("changeForm")) {
      var change = new ReservationUpdateForm();
      change.setDate(booking.getReservationDate());
      change.setTime(booking.getReservationTime());
      change.setGuests(booking.getGuestCount());
      model.addAttribute("changeForm", change);
    }
    if (!model.containsAttribute("cancelForm")) model.addAttribute("cancelForm", new ReservationCancellationForm());
    var active = tables.findByActiveTrueOrderByCodeAsc();
    int maximum = active.stream().mapToInt(t -> t.getCapacity()).max().orElse(TableKind.MAX_CAPACITY);
    model.addAttribute("maxGuests", Math.min(TableKind.MAX_CAPACITY, maximum));
    model.addAttribute("today", schedule.today());
    model.addAttribute("timeSlots", schedule.getTimeSlots());
    return deposit.status().payable();
  }
}
