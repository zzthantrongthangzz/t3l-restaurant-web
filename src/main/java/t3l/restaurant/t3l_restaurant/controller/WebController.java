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
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;
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

  public WebController(
      DishRepository dishes,
      RestaurantTableRepository restaurantTables,
      ReservationService reservationService,
      ReservationSchedule reservationSchedule,
      ReservationConfirmationAccess confirmationAccess,
      FaqRepository faqs,
      CustomerAccountService customers, ReservationDrafts drafts, Validator validator,
      PublicTableBoardService publicTableBoard, DiningOrderRepository diningOrders,
      DishService dishService, AdminInventoryService inventory) {
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
      @RequestParam(required = false) String draft, @RequestParam(required = false) Long tableId,
      @RequestParam(defaultValue = "false") boolean chooseTable,
      Authentication authentication, HttpServletRequest request,
      CsrfToken csrfToken,
      Model model) {
    csrfToken.getToken();
    UserAccount customer = customers.signedInCustomer(authentication);
    ReservationForm form = drafts.read(request.getSession(false), draft, customerId(customer));
    if (form == null) {
      if (draft != null) model.addAttribute("bookingNotice", "Phiên đặt bàn đã hết hạn. Vui lòng chọn lại lịch.");
      draft = null;
      form = new ReservationForm();
      form.setDate(date == null || date.isBefore(reservationSchedule.today()) ? reservationSchedule.today() : date);
      form.setGuests(2);
      form.setPreferredArea("");
    }
    if (date != null) form.setDate(date.isBefore(reservationSchedule.today()) ? reservationSchedule.today() : date);
    if (time != null) form.setTime(reservationSchedule.getTimeSlots().contains(time) ? time : null);
    if (customer != null) {
      form.setGuestName(customer.getFullName());
      form.setGuestPhone(customers.bookingPhone(customer.getPhone()));
      form.setGuestEmail(customer.getEmail());
    }
    Long requestedTableId = tableId != null ? tableId : chooseTable ? null : form.getTableId();
    form.setTableId(requestedTableId);
    boolean newBooking = draft == null;
    if (customer != null && (newBooking || tableId != null)) {
      draft = drafts.save(request.getSession(), form, customer.getId(), draft);
    }
    if (requestedTableId == null && (newBooking || chooseTable)) {
      LocalDate selectedDate = form.getDate() == null ? reservationSchedule.today() : form.getDate();
      addTableBoardData(model, selectedDate, time == null ? null : form.getTime());
      model.addAttribute("openBookingDialog", false);
      model.addAttribute("bookingDraft", draft);
      return "web/table-plan";
    }
    model.addAttribute("form", form);
    return bookingPage(model, customer, draft, form);
  }

  @PostMapping("/reservations/{code}/deposit-reference")
  public String submitDepositReference(@PathVariable String code, @RequestParam String reference,
      Authentication authentication, HttpServletRequest request, RedirectAttributes redirect) {
    UserAccount customer = customers.signedInCustomer(authentication);
    Reservation booking = reservationService.findVisibleByCode(code, customer,
        confirmationAccess.canView(request.getSession(false), code));
    if (booking == null || booking.getCustomerId() == null || !reservationService.owns(booking, customer)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    try {
      reservationService.submitDepositReference(booking.getId(), reference);
      redirect.addFlashAttribute("notice", "Đã gửi mã giao dịch. Nhân viên sẽ đối chiếu khoản cọc.");
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    redirect.addAttribute("code", code);
    return "redirect:/reservations/confirmation";
  }

  @PostMapping("/reservations")
  public String reserve(
      @ModelAttribute("form") ReservationForm form,
      BindingResult result,
      HttpSession session, Authentication authentication, @RequestParam(required = false) String draft,
      @RequestParam(defaultValue = "false") boolean loginOnly,
      Model model,
      RedirectAttributes redirect) {
    UserAccount customer = customers.signedInCustomer(authentication);
    boolean finalSubmission = customer != null && !customers.needsBookingProfile(customer);
    boolean partialDraft = customer == null && loginOnly;
    if (finalSubmission) {
      form.setGuestName(customer.getFullName());
      form.setGuestPhone(customers.bookingPhone(customer.getPhone()));
      form.setGuestEmail(customer.getEmail());
    }
    for (var violation : validator.validate(form)) {
      String field = violation.getPropertyPath().toString();
      if (partialDraft ? "note".equals(field) || "preferredArea".equals(field)
          || ("guests".equals(field) && form.getGuests() != null)
          : true) {
        if (!result.hasFieldErrors(field)) result.rejectValue(field, "booking.invalid", violation.getMessage());
      }
    }
    if (result.hasErrors()) return bookingPage(model, customer, draft, form);
    if (!partialDraft) {
      try {
        reservationSchedule.validateAndGetEnd(form);
      } catch (BookingException exception) {
        result.rejectValue(exception.getField(), "booking.invalid", exception.getMessage());
        return bookingPage(model, customer, draft, form);
      }
    }
    if (customer == null && !partialDraft) {
      try {
        Reservation saved = reservationService.create(form, null);
        confirmationAccess.grant(session, saved.getCode());
        redirect.addAttribute("code", saved.getCode());
        return "redirect:/reservations/confirmation";
      } catch (BookingException exception) {
        if (exception.getField() == null) result.reject("booking.unavailable", exception.getMessage());
        else result.rejectValue(exception.getField(), "booking.invalid", exception.getMessage());
      } catch (TransientDataAccessException exception) {
        result.reject("booking.busy", "Hệ thống đang xử lý nhiều yêu cầu. Vui lòng thử lại.");
      }
      return bookingPage(model, customer, draft, form);
    }
    if (!finalSubmission) {
      String id = drafts.save(session, form, customerId(customer), draft);
      if (customer == null) {
        redirect.addAttribute("returnTo", "/reservations/new?draft=" + id);
        return "redirect:/login";
      }
      redirect.addAttribute("draft", id);
      return "redirect:/reservations/new";
    }
    try {
      synchronized (session) {
        if (drafts.read(session, draft, customer.getId()) == null) {
          redirect.addFlashAttribute("bookingNotice", "Phiên đặt bàn đã hết hạn hoặc yêu cầu đã được gửi.");
          return "redirect:/reservations/new";
        }
        Reservation saved = reservationService.create(form, customer);
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
    return bookingPage(model, customer, draft, form);
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
    return bookingPage(model, customer, draft, form);
  }

  private Long customerId(UserAccount customer) {
    return customer == null ? null : customer.getId();
  }

  private String bookingPage(Model model, UserAccount customer, String draft, ReservationForm form) {
    model.addAttribute("bookingCustomer", customer != null);
    model.addAttribute("bookingDraft", draft);
    boolean missing = customer != null && customers.needsBookingProfile(customer);
    model.addAttribute("bookingProfileMissing", missing);
    if (customer != null) {
      model.addAttribute("bookingName", customer.getFullName());
      model.addAttribute("bookingPhone", customer.getPhone());
      model.addAttribute("bookingEmail", customer.getEmail());
      model.addAttribute("verifiedPhone", customer.getVerifiedPhone());
      if (missing && draft != null && !model.containsAttribute("profileForm")) {
        model.addAttribute("profileForm", customers.profileForm(customer, customers.profile(customer)));
      }
    }
    if (form.getTableId() != null && !missing) {
      LocalDate selectedDate = form.getDate() == null ? reservationSchedule.today() : form.getDate();
      RestaurantTable selectedTable = restaurantTables.findById(form.getTableId()).orElse(null);
      if (selectedTable == null || !selectedTable.isActive()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      var tableKind = selectedTable.getKind();
      addTableBoardData(model, selectedDate, form.getTime());
      model.addAttribute("selectedTable", selectedTable);
      model.addAttribute("openBookingDialog", true);
      model.addAttribute("today", reservationSchedule.today());
      model.addAttribute("maxGuests", selectedTable.getCapacity());
      model.addAttribute("depositPolicy", customer == null ? null : switch (tableKind) {
        case VIP -> "Bàn VIP: cọc tối thiểu 500.000 đ hoặc 70% giá trị món đặt trước (lấy mức cao hơn). Sau khi nhân viên duyệt, bạn gửi mã giao dịch để xác nhận cọc.";
        case LARGE -> "Bàn lớn: cọc tối thiểu 300.000 đ hoặc 70% giá trị món đặt trước (lấy mức cao hơn). Sau khi nhân viên duyệt, bạn gửi mã giao dịch để xác nhận cọc.";
        default -> "Bàn này không bắt buộc đặt cọc trước.";
      });
      return "web/table-plan";
    }
    addReservationPageData(model);
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
    }
    return "web/confirmation";
  }

  @GetMapping("/staff/login")
  public String staffLogin() {
    return "auth/staff-login";
  }

  private void addReservationPageData(Model model) {
    var activeTables = restaurantTables.findByActiveTrueOrderByCodeAsc();
    model.addAttribute("maxGuests", Math.min(20, activeTables.stream()
        .mapToInt(RestaurantTable::getCapacity).max().orElse(20)));
    model.addAttribute("schedule", reservationSchedule);
    model.addAttribute("today", reservationSchedule.today());
    model.addAttribute("timeSlots", reservationSchedule.getTimeSlots());
  }

  private void addTableBoardData(Model model, LocalDate date, LocalTime time) {
    var slots = reservationSchedule.getTimeSlots();
    LocalTime selected = time != null && slots.contains(time) ? time : null;
    model.addAttribute("selectedDate", date);
    model.addAttribute("selectedTime", selected);
    model.addAttribute("tables", publicTableBoard.forDate(date, selected));
    model.addAttribute("schedule", reservationSchedule);
    model.addAttribute("timeSlots", slots);
  }
}
