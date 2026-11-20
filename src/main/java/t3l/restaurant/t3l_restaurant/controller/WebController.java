package t3l.restaurant.t3l_restaurant.controller;

import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.dto.CustomerProfileForm;
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

  public WebController(
      DishRepository dishes,
      RestaurantTableRepository restaurantTables,
      ReservationService reservationService,
      ReservationSchedule reservationSchedule,
      ReservationConfirmationAccess confirmationAccess,
      FaqRepository faqs,
      CustomerAccountService customers, ReservationDrafts drafts, Validator validator) {
    this.dishes = dishes;
    this.restaurantTables = restaurantTables;
    this.reservationService = reservationService;
    this.reservationSchedule = reservationSchedule;
    this.confirmationAccess = confirmationAccess;
    this.faqs = faqs;
    this.customers = customers;
    this.drafts = drafts;
    this.validator = validator;
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
  public String menu(Model model) {
    var menuDishes = dishes.findByAvailableTrueOrderByCategoryNameAscNameAsc();
    model.addAttribute("dishes", menuDishes);
    model.addAttribute("dishGroups", menuDishes.stream()
        .sorted(Comparator.comparing(dish -> "Đồ uống".equals(dish.getCategoryName())))
        .collect(Collectors.groupingBy(
        dish -> dish.getCategoryName(), LinkedHashMap::new, Collectors.toList())));
    model.addAttribute("menuBanner", menuDishes.stream()
        .filter(dish -> dish.getImageUrl() != null && !dish.getImageUrl().isBlank())
        .min(Comparator.comparingInt(dish -> homeDishPriority(dish.getName(), dish.getCategoryName())))
        .orElse(null));
    return "web/menu";
  }

  @GetMapping("/faq")
  public String faq(Model model) {
    model.addAttribute("faqs", faqs.findByPublishedTrueOrderByDisplayOrderAsc());
    return "web/faq";
  }

  @GetMapping("/reservations/new")
  public String reservationForm(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) String draft, Authentication authentication, HttpServletRequest request,
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
    if (draft == null && customer != null) {
      draft = drafts.save(request.getSession(), form, customer.getId(), null);
    }
    model.addAttribute("form", form);
    return bookingPage(model, customer, draft);
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
      if (finalSubmission || (partialDraft ? "note".equals(field) || "preferredArea".equals(field)
          || ("guests".equals(field) && form.getGuests() != null)
          : !field.startsWith("guest") && !"consentAccepted".equals(field))) {
        if (!result.hasFieldErrors(field)) result.rejectValue(field, "booking.invalid", violation.getMessage());
      }
    }
    if (result.hasErrors()) return bookingPage(model, customer, draft);
    if (!partialDraft) {
      try {
        reservationSchedule.validateAndGetEnd(form);
      } catch (BookingException exception) {
        result.rejectValue(exception.getField(), "booking.invalid", exception.getMessage());
        return bookingPage(model, customer, draft);
      }
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
    return bookingPage(model, customer, draft);
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
    return bookingPage(model, customer, draft);
  }

  private Long customerId(UserAccount customer) {
    return customer == null ? null : customer.getId();
  }

  private String bookingPage(Model model, UserAccount customer, String draft) {
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
    addReservationPageData(model);
    return "web/reservation-form";
  }

  @GetMapping("/reservations/confirmation")
  public String confirmation(@RequestParam(required = false) String code,
      HttpServletRequest request, HttpServletResponse response, Authentication authentication, Model model) {
    response.setHeader("Cache-Control", "no-store, max-age=0");
    response.setHeader("Pragma", "no-cache");
    response.setHeader("Referrer-Policy", "no-referrer");
    Reservation reservation = reservationService.findVisibleByCode(code, customers.signedInCustomer(authentication),
        confirmationAccess.canView(request.getSession(false), code));
    if (reservation == null) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      model.asMap().remove("loginReturnTo");
    } else {
      model.addAttribute("reservation", reservation);
    }
    return "web/confirmation";
  }

  @GetMapping("/staff/login")
  public String staffLogin() {
    return "auth/staff-login";
  }

  private void addReservationPageData(Model model) {
    var activeTables = restaurantTables.findByActiveTrueOrderByCodeAsc();
    model.addAttribute("areas", activeTables.stream().map(RestaurantTable::getTableType)
        .distinct().sorted().toList());
    model.addAttribute("maxGuests", Math.min(20, activeTables.stream()
        .mapToInt(RestaurantTable::getCapacity).max().orElse(20)));
    model.addAttribute("schedule", reservationSchedule);
    model.addAttribute("today", reservationSchedule.today());
    model.addAttribute("timeSlots", reservationSchedule.getTimeSlots());
  }
}
