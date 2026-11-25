package t3l.restaurant.t3l_restaurant.controller;

import jakarta.validation.Valid;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.dto.MenuOrderForm;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.dto.CustomerProfileForm;
import t3l.restaurant.t3l_restaurant.entity.Dish;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.*;
import t3l.restaurant.t3l_restaurant.service.ReservationService;
import t3l.restaurant.t3l_restaurant.service.ReservationSchedule;
import t3l.restaurant.t3l_restaurant.service.ReservationConfirmationAccess;
import t3l.restaurant.t3l_restaurant.service.BookingException;
import t3l.restaurant.t3l_restaurant.service.CustomerAccountService;
import t3l.restaurant.t3l_restaurant.service.CustomerAccountService.DuplicateEmailException;
import t3l.restaurant.t3l_restaurant.service.ReservationDrafts;
import t3l.restaurant.t3l_restaurant.service.PublicTableBoardService;
import t3l.restaurant.t3l_restaurant.service.DishService;
import t3l.restaurant.t3l_restaurant.service.AdminInventoryService;
import t3l.restaurant.t3l_restaurant.service.PreorderService;
import t3l.restaurant.t3l_restaurant.service.PaymentIntentService;
import t3l.restaurant.t3l_restaurant.service.PaymentVerificationService;
import t3l.restaurant.t3l_restaurant.service.SepayProperties;
import t3l.restaurant.t3l_restaurant.service.TrackingService;

@Controller
public class WebController {
  private final DishRepository dishes;
  private final RestaurantTableRepository restaurantTables;
  private final ReservationService reservationService;
  private final ReservationSchedule reservationSchedule;
  private final ReservationConfirmationAccess confirmationAccess;
  private final FaqRepository faqs;
  private final CustomerAccountService customers;
  private final ReservationDrafts drafts;
  private final Validator validator;
  private final PublicTableBoardService publicTableBoard;
  private final DiningOrderRepository diningOrders;
  private final DishService dishService;
  private final AdminInventoryService inventory;
  private final DiningPaymentRepository diningPayments;
  private final PreorderService preorders;
  private final PaymentIntentService paymentIntents;
  private final PaymentVerificationService paymentVerification;
  private final SepayProperties sepay;
  private final TrackingService tracking;

  public WebController(
      DishRepository dishes,
      RestaurantTableRepository restaurantTables,
      ReservationService reservationService,
      ReservationSchedule reservationSchedule,
      ReservationConfirmationAccess confirmationAccess,
      FaqRepository faqs,
      CustomerAccountService customers, ReservationDrafts drafts, Validator validator,
      PublicTableBoardService publicTableBoard, DiningOrderRepository diningOrders, DiningPaymentRepository diningPayments,
      PreorderService preorders, PaymentIntentService paymentIntents,
      PaymentVerificationService paymentVerification, SepayProperties sepay, DishService dishService, AdminInventoryService inventory,
      TrackingService tracking) {
    this.dishes = dishes;
    this.restaurantTables = restaurantTables;
    this.reservationService = reservationService;
    this.reservationSchedule = reservationSchedule;
    this.confirmationAccess = confirmationAccess;
    this.faqs = faqs;
    this.customers = customers;
    this.drafts = drafts;
    this.validator = validator;
    this.publicTableBoard = publicTableBoard;
    this.diningOrders = diningOrders;
    this.dishService = dishService;
    this.inventory = inventory;
    this.diningPayments = diningPayments;
    this.preorders = preorders;
    this.paymentIntents = paymentIntents;
    this.paymentVerification = paymentVerification;
    this.sepay = sepay;
    this.tracking = tracking;
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
    if (!isEmployee(authentication)) return "/reservations/confirmation?code=" + booking.getCode();
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
      @RequestParam(required = false) Long tableId, @RequestParam(defaultValue = "false") boolean choose,
      @RequestParam(defaultValue = "false") boolean chooseTable,
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
    form.setGuests(boardGuests(form.getGuests()));
    if (time != null) form.setTime(time);
    if (choose || chooseTable) form.setTableId(null);
    if (tableId != null) form.setTableId(tableId);

    var rules = reservationSchedule.rules();
    var slots = reservationSchedule.futureSlots(form.getDate(), rules);
    if (pastDate || !slots.contains(form.getTime())) {
      if (form.getTime() != null || pastDate) {
        model.addAttribute("bookingNotice", "Khung giờ đã chọn không còn khả dụng. Vui lòng chọn lại.");
        form.setTableId(null);
      }
      form.setTime(slots.isEmpty() ? null : slots.getFirst());
    }

    var board = publicTableBoard.forSlot(form.getDate(), form.getTime(), form.getGuests(), rules.duration());
    if (form.getTableId() != null) {
      Long selectedId = form.getTableId();
      boolean available = board.stream().anyMatch(item -> item.table().getId().equals(selectedId) && "FREE".equals(item.state()));
      if (!available) {
        form.setTableId(null);
        model.addAttribute("bookingNotice", "Bàn đã chọn không còn phù hợp hoặc vừa có lượt đặt. Vui lòng chọn bàn khác.");
      }
    }
    if (resumed || customer != null || form.getTableId() != null) {
      draft = drafts.save(request.getSession(), form, customerId(customer), draft);
    }
    if (customer != null && !customers.needsBookingProfile(customer)) fillContact(form, customer);
    if (choose || chooseTable || (tableId != null && form.getTableId() != null)) {
      tracking.booking(request, authentication, tableId != null && form.getTableId() != null, null);
    }
    model.addAttribute("form", form);
    return bookingPage(model, customer, draft, form, rules, board, !choose && !chooseTable && (resumed || form.getTableId() != null));
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
    return "redirect:/reservations/confirmation";
  }

  @GetMapping("/reservations/{code}/payment-status")
  @ResponseBody
  public PaymentIntentService.PaymentStatus paymentStatus(@PathVariable String code,
      Authentication authentication, HttpServletRequest request) {
    Reservation booking = visibleReservation(code, authentication, request);
    if (booking == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    return paymentIntents.depositStatus(booking);
  }

  @PostMapping("/reservations/{code}/payment-demo")
  public String demoDeposit(@PathVariable String code, Authentication authentication,
      HttpServletRequest request, RedirectAttributes redirect) {
    Reservation booking = visibleReservation(code, authentication, request);
    if (booking == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    try {
      var checkout = paymentIntents.depositCheckout(booking.getId());
      if (checkout == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
      paymentVerification.simulate(checkout.intent().getPaymentCode());
      redirect.addFlashAttribute("notice", "Đã ghi nhận khoản cọc DEMO; nhân viên có thể xác nhận đặt bàn.");
    } catch (org.springframework.web.server.ResponseStatusException exception) {
      throw exception;
    } catch (BookingException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    } catch (RuntimeException exception) {
      redirect.addFlashAttribute("error", "Chưa thể ghi nhận giao dịch DEMO. Vui lòng tải lại trang và thử lại.");
    }
    redirect.addAttribute("code", code);
    return "redirect:/reservations/confirmation";
  }

  private Reservation visibleReservation(String code, Authentication authentication, HttpServletRequest request) {
    return reservationService.findVisibleByCode(code, customers.signedInCustomer(authentication),
        confirmationAccess.canView(request.getSession(false), code));
  }

  @PostMapping("/reservations")
  public String reserve(
      @ModelAttribute("form") ReservationForm form, BindingResult result,
      HttpSession session, HttpServletRequest request, Authentication authentication, @RequestParam(required = false) String draft,
      @RequestParam(defaultValue = "false") boolean loginOnly, @RequestParam(defaultValue = "false") boolean choose,
      Model model, RedirectAttributes redirect) {
    UserAccount customer = customers.signedInCustomer(authentication);
    boolean incompleteProfile = customer != null && customers.needsBookingProfile(customer);
    if (customer != null && !incompleteProfile) fillContact(form, customer);
    boolean saveForLogin = customer == null && loginOnly;
    if (customer == null && !saveForLogin) {
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
    boolean draftOnly = choose || saveForLogin || incompleteProfile;

    for (var violation : validator.validate(form)) {
      String field = violation.getPropertyPath().toString();
      boolean optionalForDraft = field.startsWith("guest") && !"guests".equals(field)
          || "consentAccepted".equals(field) || ("date".equals(field) && form.getDate() == null)
          || ("time".equals(field) && form.getTime() == null) || ("guests".equals(field) && form.getGuests() == null);
      if ((!draftOnly || !optionalForDraft) && !result.hasFieldErrors(field)) {
        result.rejectValue(field, "booking.invalid", violation.getMessage());
      }
    }
    if (result.hasErrors()) return redisplayBooking(model, customer, draft, form);
    if (draftOnly) {
      String id = drafts.save(session, form, customerId(customer), draft);
      if (saveForLogin) {
        redirect.addAttribute("returnTo", "/reservations/new?draft=" + id);
        return "redirect:/login";
      }
      redirect.addAttribute("draft", id);
      if (choose) redirect.addAttribute("choose", true);
      return "redirect:/reservations/new";
    }

    try {
      synchronized (session) {
        if ((customer != null || draft != null) && drafts.read(session, draft, customerId(customer)) == null) {
          redirect.addFlashAttribute("bookingNotice", "Phiên đặt bàn đã hết hạn hoặc yêu cầu đã được gửi.");
          return "redirect:/reservations/new";
        }
        Reservation saved = reservationService.create(form, customer);
        tracking.booking(request, authentication, true, saved);
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

  @PostMapping("/reservations/profile")
  public String completeBookingProfile(@Valid @ModelAttribute("profileForm") CustomerProfileForm profile,
      BindingResult errors, @RequestParam String draft, Authentication authentication, HttpSession session,
      Model model, RedirectAttributes redirect) {
    UserAccount customer = customers.signedInCustomer(authentication);
    if (customer == null) {
      redirect.addAttribute("returnTo", "/reservations/new");
      return "redirect:/login";
    }
    ReservationForm form = drafts.read(session, draft, customer.getId());
    if (form == null) return "redirect:/reservations/new";
    if (customer.getVerifiedPhone() == null && customers.bookingPhone(profile.getPhone()) == null) {
      errors.rejectValue("phone", "booking.phone.required", "Vui lòng nhập số điện thoại hợp lệ.");
    }
    if (!errors.hasErrors()) {
      try {
        customers.updateProfile(customer.getId(), profile);
        redirect.addAttribute("draft", draft);
        return "redirect:/reservations/new";
      } catch (DuplicateEmailException | DataIntegrityViolationException exception) {
        errors.rejectValue("email", "email.duplicate", "Email đã được sử dụng.");
      }
    }
    model.addAttribute("form", form);
    return redisplayBooking(model, customer, draft, form);
  }

  private Long customerId(UserAccount customer) {
    return customer == null ? null : customer.getId();
  }

  private int boardGuests(Integer guests) {
    return guests == null ? 2 : Math.clamp(guests, 1, 20);
  }

  private void fillContact(ReservationForm form, UserAccount customer) {
    form.setGuestName(customer.getFullName());
    form.setGuestPhone(customers.bookingPhone(customer.getPhone()));
    form.setGuestEmail(customer.getEmail());
  }

  private String redisplayBooking(Model model, UserAccount customer, String draft, ReservationForm form) {
    var rules = reservationSchedule.rules();
    var date = form.getDate() == null ? reservationSchedule.today() : form.getDate();
    var slots = reservationSchedule.futureSlots(date, rules);
    var time = slots.contains(form.getTime()) ? form.getTime() : slots.isEmpty() ? null : slots.getFirst();
    var board = publicTableBoard.forSlot(date, time, boardGuests(form.getGuests()), rules.duration());
    return bookingPage(model, customer, draft, form, rules, board, false);
  }

  private String bookingPage(Model model, UserAccount customer, String draft, ReservationForm form,
      ReservationSchedule.Rules rules, List<PublicTableBoardService.TableView> board, boolean openProfile) {
    var today = reservationSchedule.today();
    var date = form.getDate() == null ? today : form.getDate();
    var slots = reservationSchedule.futureSlots(date, rules);
    var time = slots.contains(form.getTime()) ? form.getTime() : slots.isEmpty() ? null : slots.getFirst();
    boolean missing = customer != null && customers.needsBookingProfile(customer);
    boolean showProfile = missing && draft != null && (openProfile || model.containsAttribute("profileForm"));
    model.addAttribute("bookingCustomer", customer != null);
    model.addAttribute("bookingDraft", draft);
    model.addAttribute("bookingProfileMissing", missing);
    model.addAttribute("showBookingProfile", showProfile);
    if (showProfile && !model.containsAttribute("profileForm")) {
      var profile = customers.profileForm(customer, customers.profile(customer), form.getGuestName());
      if (customers.bookingPhone(profile.getPhone()) == null) profile.setPhone(customers.bookingPhone(form.getGuestPhone()));
      if (profile.getEmail() == null || profile.getEmail().isBlank()) profile.setEmail(form.getGuestEmail());
      model.addAttribute("profileForm", profile);
    }
    if (customer != null) {
      model.addAttribute("bookingName", customer.getFullName());
      model.addAttribute("verifiedPhone", customer.getVerifiedPhone());
    }

    var selectedTable = form.getTableId() == null ? null : restaurantTables.findById(form.getTableId()).orElse(null);
    model.addAttribute("selectedDate", date);
    model.addAttribute("selectedTime", time);
    model.addAttribute("selectedGuests", boardGuests(form.getGuests()));
    model.addAttribute("today", today);
    model.addAttribute("timeSlots", slots);
    model.addAttribute("duration", rules.duration());
    model.addAttribute("tables", board);
    model.addAttribute("selectedTable", selectedTable);
    if (selectedTable != null) {
      model.addAttribute("minimumDeposit", reservationService.minimumDeposit(selectedTable));
      model.addAttribute("pickerPaid", java.math.BigDecimal.ZERO);
      model.addAttribute("depositRefundTerms", reservationService.depositRefundTerms());
      model.addAttribute("preorderCards", preorders.picker(form, null));
    }
    return "web/table-plan";
  }

  @GetMapping("/reservations/confirmation")
  public String confirmation(@RequestParam(required = false) String code,
      HttpServletRequest request, HttpServletResponse response, Authentication authentication, Model model) {
    response.setHeader("Cache-Control", "no-store, max-age=0");
    response.setHeader("Pragma", "no-cache");
    response.setHeader("Referrer-Policy", "no-referrer");
    UserAccount customer = customers.signedInCustomer(authentication);
    Reservation reservation = reservationService.findVisibleByCode(code, customer,
        confirmationAccess.canView(request.getSession(false), code));
    if (reservation == null) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      model.asMap().remove("loginReturnTo");
    } else {
      model.addAttribute("reservation", reservation);
      var order = diningOrders.findByReservation_Id(reservation.getId()).orElse(null);
      model.addAttribute("preorder", order);
      model.addAttribute("canAddMenuItems", reservationService.owns(reservation, customer)
          && reservationService.canAddMenuItems(reservation, order, false));
      if (order != null) model.addAttribute("payment", diningPayments.findByOrder_Id(order.getId()).orElse(null));
      model.addAttribute("depositRefundTerms", reservationService.depositRefundTerms());
      model.addAttribute("depositOverdue", reservation.hasExpiredDeposit(java.time.LocalDateTime.now(reservationSchedule.getClock())));
      var checkout = paymentIntents.depositCheckout(reservation.getId());
      var paymentStatus = paymentIntents.depositStatus(reservation);
      model.addAttribute("depositCheckout", checkout);
      model.addAttribute("paymentStatus", paymentStatus);
      if (!reservation.isDepositDemo() && "WAITING".equals(paymentStatus.status())
          && (checkout != null && checkout.hasQr() || !paymentIntents.usesQr())) {
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
