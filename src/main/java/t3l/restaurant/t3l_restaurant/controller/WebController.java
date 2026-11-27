package t3l.restaurant.t3l_restaurant.controller;

import jakarta.validation.Validator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.dto.MenuOrderForm;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.entity.Dish;
import t3l.restaurant.t3l_restaurant.entity.PaymentIntent;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.TableKind;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.*;
import t3l.restaurant.t3l_restaurant.service.ReservationService;
import t3l.restaurant.t3l_restaurant.service.ReservationSchedule;
import t3l.restaurant.t3l_restaurant.service.ReservationConfirmationAccess;
import t3l.restaurant.t3l_restaurant.service.ReservationLookupAccess;
import t3l.restaurant.t3l_restaurant.service.BookingException;
import t3l.restaurant.t3l_restaurant.service.CustomerAccountService;
import t3l.restaurant.t3l_restaurant.service.ReservationDrafts;
import t3l.restaurant.t3l_restaurant.service.DishService;
import t3l.restaurant.t3l_restaurant.service.AdminInventoryService;
import t3l.restaurant.t3l_restaurant.service.PreorderService;
import t3l.restaurant.t3l_restaurant.service.PaymentIntentService;
import t3l.restaurant.t3l_restaurant.service.PaymentVerificationService;
import t3l.restaurant.t3l_restaurant.service.SepayProperties;
import t3l.restaurant.t3l_restaurant.service.TrackingService;

@Controller
public class WebController {
  private static final Logger log = LoggerFactory.getLogger(WebController.class);

  private final DishRepository dishes;
  private final ReservationService reservationService;
  private final ReservationSchedule reservationSchedule;
  private final ReservationConfirmationAccess confirmationAccess;
  private final ReservationLookupAccess lookupAccess;
  private final FaqRepository faqs;
  private final CustomerAccountService customers;
  private final ReservationDrafts drafts;
  private final Validator validator;
  private final DiningOrderRepository diningOrders;
  private final DishService dishService;
  private final AdminInventoryService inventory;
  private final DiningPaymentRepository diningPayments;
  private final PreorderService preorders;
  private final PaymentIntentService paymentIntents;
  private final PaymentVerificationService paymentVerification;
  private final SepayProperties sepay;
  private final TrackingService tracking;
  private final t3l.restaurant.t3l_restaurant.service.VoucherService vouchers;

  public WebController(
      DishRepository dishes,
      ReservationService reservationService,
      ReservationSchedule reservationSchedule,
      ReservationConfirmationAccess confirmationAccess, ReservationLookupAccess lookupAccess,
      FaqRepository faqs,
      CustomerAccountService customers, ReservationDrafts drafts, Validator validator,
      DiningOrderRepository diningOrders, DiningPaymentRepository diningPayments,
      PreorderService preorders, PaymentIntentService paymentIntents,
      PaymentVerificationService paymentVerification, SepayProperties sepay, DishService dishService, AdminInventoryService inventory,
      TrackingService tracking,t3l.restaurant.t3l_restaurant.service.VoucherService vouchers) {
    this.dishes = dishes;
    this.reservationService = reservationService;
    this.reservationSchedule = reservationSchedule;
    this.confirmationAccess = confirmationAccess;
    this.lookupAccess = lookupAccess;
    this.faqs = faqs;
    this.customers = customers;
    this.drafts = drafts;
    this.validator = validator;
    this.diningOrders = diningOrders;
    this.dishService = dishService;
    this.inventory = inventory;
    this.diningPayments = diningPayments;
    this.preorders = preorders;
    this.paymentIntents = paymentIntents;
    this.paymentVerification = paymentVerification;
    this.sepay = sepay;
    this.tracking = tracking;
    this.vouchers=vouchers;
  }

  @GetMapping("/")
  public String home(Model model) {
    model.addAttribute(
        "dishes",
        dishes.findByAvailableTrueOrderByCategoryNameAscNameAsc().stream()
            .sorted(Comparator.comparingInt(dish -> homeDishPriority(dish.getName(), dish.getCategoryName())))
            .limit(3)
            .toList());
    return "web/home";
  }

  private int homeDishPriority(String name, String category) {
    String normalizedName = name.toLowerCase(Locale.ROOT);
    if (normalizedName.contains("bít tết")) return 0;
    if (normalizedName.contains("bò nướng tảng")) return 1;
    if (normalizedName.contains("cá hồi")) return 2;
    return "Món chính".equals(category) ? 3 : 4;
  }

  @GetMapping("/menu")
  public String menu(@RequestParam(required = false) String booking, Authentication authentication,
      HttpServletResponse response, Model model) {
    boolean admin = authentication != null && authentication.getAuthorities().stream()
        .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    var menuDishes = admin && booking == null ? dishService.findAll() : dishService.findAvailable();
    if (booking != null) {
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("Referrer-Policy", "no-referrer");
      var reservation = requireMenuBooking(booking, authentication);
      var order = diningOrders.findByReservation_Id(reservation.getId()).orElse(null);
      if (!reservationService.canAddMenuItems(reservation, order, isEmployee(authentication))) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Lượt đặt bàn này hiện không thể thêm món.");
      }
      model.addAttribute("hideChat", true);
      model.addAttribute("menuBooking", reservation);
      model.addAttribute("menuOrder", order);
      model.addAttribute("menuOrderedDishIds", order == null ? List.of() : order.getItems().stream()
          .filter(item -> !item.isCancelled()).map(item -> item.getDishId()).toList());
      model.addAttribute("bookingReturn", bookingReturn(reservation, authentication));
    }
    model.addAttribute("dishGroups", menuDishes.stream()
        .sorted(Comparator.comparing(dish -> "Đồ uống".equals(dish.getCategoryName())))
        .collect(Collectors.groupingBy(Dish::getCategoryName, LinkedHashMap::new, Collectors.toList())));
    model.addAttribute("dishCount", menuDishes.size());
    if (admin) {
      model.addAttribute("hiddenCategories", inventory.sectionList("MENU").stream()
          .filter(section -> !section.active()).map(AdminInventoryService.SectionRow::name).toList());
    }
    return "web/menu";
  }

  @PostMapping("/reservations/{code}/preorder")
  public String addMenuItems(@PathVariable String code, @ModelAttribute MenuOrderForm form, BindingResult binding,
      Authentication authentication, RedirectAttributes redirect) {
    Reservation booking = requireMenuBooking(code, authentication);
    try {
      if (binding.hasErrors()) {
        throw new BookingException(null, "Thông tin món hoặc số lượng không hợp lệ.");
      }
      reservationService.addMenuItems(booking.getId(), form, customers.signedInCustomer(authentication),
          authentication.getName(), isEmployee(authentication));
      redirect.addFlashAttribute("notice", "Đã lưu món vào đơn của lượt đặt bàn.");
    } catch (BookingException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
      var current = reservationService.findByCode(code);
      var order = diningOrders.findByReservation_Id(booking.getId()).orElse(null);
      if (current != null && reservationService.canAddMenuItems(current, order, isEmployee(authentication))) {
        redirect.addAttribute("booking", code);
        return "redirect:/menu";
      }
    }
    return "redirect:" + bookingReturn(booking, authentication);
  }

  private Reservation requireMenuBooking(String code, Authentication authentication) {
    Reservation booking = isEmployee(authentication) && code.matches("T3L-[A-Z0-9-]{4,16}")
        ? reservationService.findByCode(code)
        : reservationService.findVisibleByCode(code, customers.signedInCustomer(authentication), false);
    if (booking == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return booking;
  }

  private boolean isEmployee(Authentication authentication) {
    return authentication != null && authentication.getAuthorities().stream().anyMatch(authority ->
        "ROLE_ADMIN".equals(authority.getAuthority()) || "ROLE_STAFF".equals(authority.getAuthority()));
  }

  private String bookingReturn(Reservation booking, Authentication authentication) {
    if (!isEmployee(authentication)) return "/reservations/lookup?code=" + booking.getCode();
    boolean admin = authentication.getAuthorities().stream()
        .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    return (admin ? "/admin" : "/staff") + "/reservations/" + booking.getId();
  }

  @GetMapping("/faq")
  public String faq(Model model) {
    model.addAttribute("faqs", faqs.findByPublishedTrueOrderByDisplayOrderAsc());
    return "web/faq";
  }

  @GetMapping("/reservations/new")
  public String reservationForm(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time,
      @RequestParam(required = false) Integer guests, @RequestParam(required = false) String draft,
      @RequestParam(defaultValue = "false") boolean choose,
      Authentication authentication, HttpServletRequest request, CsrfToken csrfToken, Model model) {
    csrfToken.getToken();
    UserAccount customer = customers.signedInCustomer(authentication);
    if (draft != null && draft.isBlank()) draft = null;
    ReservationForm form = drafts.read(request.getSession(false), draft, customerId(customer));
    boolean resumed = form != null;
    if (!resumed) {
      if (draft != null) model.addAttribute("bookingNotice", "Phiên đặt bàn đã hết hạn. Vui lòng chọn lại lịch.");
      draft = null;
      form = new ReservationForm();
    }

    var today = reservationSchedule.today();
    var requestedDate = date == null ? form.getDate() : date;
    boolean pastDate = requestedDate != null && requestedDate.isBefore(today);
    form.setDate(requestedDate == null || pastDate ? today : requestedDate);
    if (guests != null) form.setGuests(guests);
    form.setGuests(form.getGuests() == null ? 2 : Math.clamp(form.getGuests(), 1, TableKind.MAX_CAPACITY));
    if (time != null) form.setTime(time);
    form.setTableId(null);
    form.setPreferredArea(null);

    var rules = reservationSchedule.rules();
    var slots = reservationSchedule.futureSlots(form.getDate(), rules);
    if (pastDate || !slots.contains(form.getTime())) {
      if (form.getTime() != null || pastDate) {
        model.addAttribute("bookingNotice", "Khung giờ đã chọn không còn khả dụng. Vui lòng chọn lại.");
      }
      form.setTime(null);
    }
    if (customer != null) {
      if (!customers.needsBookingProfile(customer)) fillContact(form, customer);
      prefillMissingContact(form, customer);
    }
    model.addAttribute("bookingResumeReview", resumed && !choose && form.getTime() != null
        && (form.isConsentAccepted() || !form.getPreorderQuantities().isEmpty() || !form.getPreorderDishIds().isEmpty()));
    draft = drafts.save(request.getSession(), form, customerId(customer), draft);
    model.addAttribute("form", form);
    return bookingPage(model, customer, draft, form, rules);
  }

  @PostMapping("/reservations/{code}/deposit-reference")
  public String submitDepositReference(@PathVariable String code, @RequestParam String reference,
      Authentication authentication, HttpServletRequest request, RedirectAttributes redirect) {
    var booking = visibleReservation(code, authentication, request);
    if (booking == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    try {
      reservationService.submitDepositReference(booking.getId(), booking.getCustomerId(), reference);
      redirect.addFlashAttribute("notice", "Đã gửi mã giao dịch. Nhân viên sẽ đối chiếu khoản cọc.");
    } catch (BookingException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    redirect.addAttribute("code", code);
    return booking.getCustomerId() == null ? "redirect:/reservations/confirmation" : "redirect:/reservations/lookup#booking-payment";
  }

  @GetMapping("/reservations/{code}/payment-status")
  @ResponseBody
  public PaymentIntentService.CustomerDepositStatus paymentStatus(@PathVariable String code,
      Authentication authentication, HttpServletRequest request) {
    Reservation booking = visibleReservation(code, authentication, request);
    if (booking == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    return paymentIntents.customerDeposit(booking).status();
  }

  @PostMapping("/reservations/{code}/payment-demo")
  public String demoDeposit(@PathVariable String code, Authentication authentication,
      HttpServletRequest request, RedirectAttributes redirect) {
    Reservation booking = visibleReservation(code, authentication, request);
    if (booking == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    try {
      var checkout = paymentIntents.customerDeposit(booking).checkout();
      if (checkout == null) throw new BookingException(null, "Lượt đặt không còn chờ thanh toán cọc.");
      var intent = paymentVerification.simulateDepositAsPaid(checkout.intent().getPaymentCode());
      if (!List.of(PaymentIntent.RECEIVED, PaymentIntent.APPLIED).contains(intent.getStatus())) {
        throw new BookingException(null, "Chưa ghi nhận cọc DEMO. Hãy kiểm tra trạng thái thanh toán của lượt đặt.");
      }
      redirect.addFlashAttribute("notice", "Đã ghi nhận khoản cọc; nhân viên có thể xác nhận đặt bàn.");
    } catch (org.springframework.web.server.ResponseStatusException exception) {
      throw exception;
    } catch (BookingException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    } catch (RuntimeException exception) {
      log.warn("Could not simulate a deposit for reservation {}", booking.getId(), exception);
      redirect.addFlashAttribute("error", "Chưa thể ghi nhận khoản cọc. Vui lòng tải lại trang và thử lại.");
    }
    redirect.addAttribute("code", code);
    return booking.getCustomerId() == null ? "redirect:/reservations/confirmation" : "redirect:/reservations/lookup#booking-payment";
  }

  private Reservation visibleReservation(String code, Authentication authentication, HttpServletRequest request) {
    return reservationService.findVisibleByCode(code, customers.signedInCustomer(authentication),
        confirmationAccess.canView(request.getSession(false), code) || lookupAccess.canView(request.getSession(false), code));
  }

  @InitBinder("form")
  void protectTableSelection(WebDataBinder binder) {
    binder.setDisallowedFields("tableId", "preferredArea");
  }

  @PostMapping("/reservations")
  public String reserve(
      @ModelAttribute("form") ReservationForm form, BindingResult result,
      HttpSession session, HttpServletRequest request, Authentication authentication, @RequestParam(required = false) String draft,
      @RequestParam(defaultValue = "false") boolean loginOnly, @RequestParam(defaultValue = "false") boolean choose,
      Model model, RedirectAttributes redirect) {
    UserAccount customer = customers.signedInCustomer(authentication);
    boolean incompleteProfile = customer != null && customers.needsBookingProfile(customer);
    if (customer != null) {
      if (incompleteProfile) prefillMissingContact(form, customer);
      else fillContact(form, customer);
    }
    boolean saveForLogin = customer == null && loginOnly;
    boolean saveDraft = choose || saveForLogin;
    if (customer == null && !saveDraft) {
      form.setPreorderDishIds(List.of());
      form.setPreorderQuantities(Map.of());
      form.setPreorderNote(null);
      form.setPreorderExpectedTotal(null);
    } else if (!result.hasFieldErrors("preorderQuantities")) {
      try {
        preorders.selection(form);
      } catch (BookingException exception) {
        result.rejectValue("preorderQuantities", "booking.invalid", exception.getMessage());
      }
    }
    for (var violation : validator.validate(form)) {
      String field = violation.getPropertyPath().toString();
      boolean optionalForDraft = field.startsWith("guest") && !"guests".equals(field)
          || "consentAccepted".equals(field) || ("date".equals(field) && form.getDate() == null)
          || ("time".equals(field) && form.getTime() == null) || ("guests".equals(field) && form.getGuests() == null);
      if ((!saveDraft || !optionalForDraft) && !result.hasFieldErrors(field)) {
        result.rejectValue(field, "booking.invalid", violation.getMessage());
      }
    }
    if (result.hasErrors()) return redisplayBooking(model, customer, draft, form);
    if (saveDraft) {
      String id = drafts.save(session, form, customerId(customer), draft);
      if (saveForLogin) {
        redirect.addAttribute("returnTo", "/reservations/new?draft=" + id);
        return "redirect:/login";
      }
      redirect.addAttribute("draft", id);
      redirect.addAttribute("choose", true);
      return "redirect:/reservations/new";
    }

    try {
      synchronized (session) {
        if ((customer != null || draft != null) && drafts.read(session, draft, customerId(customer)) == null) {
          redirect.addFlashAttribute("bookingNotice", "Phiên đặt bàn đã hết hạn hoặc yêu cầu đã được gửi.");
          return "redirect:/reservations/new";
        }
        Reservation saved = reservationService.create(form, customer);
        tracking.booking(request, authentication, true, saved,
            form.getVoucherCode() != null && !form.getVoucherCode().isBlank());
        drafts.remove(session, draft);
        confirmationAccess.grant(session, saved.getCode());
        redirect.addAttribute("code", saved.getCode());
        return "redirect:/reservations/confirmation";
      }
    } catch (BookingException exception) {
      if (exception.getField() == null) result.reject("booking.unavailable", exception.getMessage());
      else result.rejectValue(exception.getField(), "booking.invalid", exception.getMessage());
    } catch (TransientDataAccessException exception) {
      result.reject("booking.busy", "Hệ thống đang xử lý nhiều yêu cầu. Vui lòng thử lại trong ít giây.");
    }
    return redisplayBooking(model, customer, draft, form);
  }

  private Long customerId(UserAccount customer) {
    return customer == null ? null : customer.getId();
  }

  private void fillContact(ReservationForm form, UserAccount customer) {
    form.setGuestName(customer.getFullName());
    form.setGuestPhone(customers.bookingPhone(customer.getPhone()));
  }

  private void prefillMissingContact(ReservationForm form, UserAccount customer) {
    if (form.getGuestName() == null || form.getGuestName().isBlank()) {
      form.setGuestName(customer.getFullName());
    }
    if (form.getGuestPhone() == null || form.getGuestPhone().isBlank()) {
      form.setGuestPhone(customers.bookingPhone(customer.getPhone()));
    }
    if (form.getGuestEmail() == null || form.getGuestEmail().isBlank()) {
      form.setGuestEmail(customer.getEmail());
    }
  }

  private String redisplayBooking(Model model, UserAccount customer, String draft, ReservationForm form) {
    return bookingPage(model, customer, draft, form, reservationSchedule.rules());
  }

  private String bookingPage(Model model, UserAccount customer, String draft, ReservationForm form,
      ReservationSchedule.Rules rules) {
    model.addAttribute("bookingCustomer", customer != null);
    model.addAttribute("bookingDraft", draft);
    model.addAttribute("bookingProfileMissing", customer != null && customers.needsBookingProfile(customer));
    model.addAttribute("today", reservationSchedule.today());
    model.addAttribute("timeSlots", rules.slots());
    model.addAttribute("bookingZone", reservationSchedule.getTimeZone());
    model.addAttribute("depositRefundTerms", reservationService.depositRefundTerms());
    model.addAttribute("preorderCards", preorders.picker(form, null));
    model.addAttribute("selectedVoucher", form.getVoucherCode());
    if (customer != null) {
      model.addAttribute("voucherOffers", vouchers.offers(customer.getId(), false));
    }
    return "web/reservation-form";
  }

  @GetMapping("/reservations/confirmation")
  public String confirmation(@RequestParam(required = false) String code,
      HttpServletRequest request, HttpServletResponse response, Authentication authentication, Model model) {
    response.setHeader("Cache-Control", "no-store, max-age=0");
    response.setHeader("Pragma", "no-cache");
    response.setHeader("Referrer-Policy", "no-referrer");
    UserAccount customer = customers.signedInCustomer(authentication);
    Reservation reservation = reservationService.findVisibleByCode(code, customer,
        confirmationAccess.canView(request.getSession(false), code) || lookupAccess.canView(request.getSession(false), code));
    if (reservation == null) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      model.asMap().remove("loginReturnTo");
    } else {
      model.addAttribute("reservation", reservation);
      var order = diningOrders.findByReservation_Id(reservation.getId()).orElse(null);
      model.addAttribute("preorder", order);
      if (order != null) model.addAttribute("payment", diningPayments.findByOrder_Id(order.getId()).orElse(null));
      model.addAttribute("depositRefundTerms", reservationService.depositRefundTerms());
      var deposit = paymentIntents.customerDeposit(reservation);
      model.addAttribute("depositCheckout", deposit.checkout());
      model.addAttribute("paymentStatus", deposit.status());
      if (!reservation.isDepositDemo() && deposit.status().payable()) {
        tracking.depositOpened(request, authentication, reservation.getId());
      }
      model.addAttribute("demoEnabled", sepay.demoAllowed());
      model.addAttribute("qrPayments", paymentIntents.usesQr());
    }
    return "web/confirmation";
  }

  @GetMapping("/staff/login")
  public String staffLogin() {
    return "auth/staff-login";
  }

}
