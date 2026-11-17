package t3l.restaurant.t3l_restaurant.controller;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.dto.CashSaleForm;
import t3l.restaurant.t3l_restaurant.entity.StaffShift;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;
import t3l.restaurant.t3l_restaurant.repository.*;
import t3l.restaurant.t3l_restaurant.service.ReservationService;
import t3l.restaurant.t3l_restaurant.service.ReservationSchedule;
import t3l.restaurant.t3l_restaurant.service.ReservationConfirmationAccess;
import t3l.restaurant.t3l_restaurant.service.ReservationManagementService;
import t3l.restaurant.t3l_restaurant.service.BookingException;
import t3l.restaurant.t3l_restaurant.service.StaffShiftService;

@Controller
public class WebController {
  private final DishRepository dishes;
  private final RestaurantTableRepository restaurantTables;
  private final ReservationService reservationService;
  private final ReservationSchedule reservationSchedule;
  private final ReservationConfirmationAccess confirmationAccess;
  private final ReservationManagementService reservationManagement;
  private final FaqRepository faqs;
  private final StaffShiftService shifts;

  public WebController(
      DishRepository dishes,
      RestaurantTableRepository restaurantTables,
      ReservationService reservationService,
      ReservationSchedule reservationSchedule,
      ReservationConfirmationAccess confirmationAccess,
      ReservationManagementService reservationManagement,
      FaqRepository faqs,
      StaffShiftService shifts) {
    this.dishes = dishes;
    this.restaurantTables = restaurantTables;
    this.reservationService = reservationService;
    this.reservationSchedule = reservationSchedule;
    this.confirmationAccess = confirmationAccess;
    this.reservationManagement = reservationManagement;
    this.faqs = faqs;
    this.shifts = shifts;
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
      CsrfToken csrfToken,
      Model model) {
    csrfToken.getToken();
    ReservationForm form = new ReservationForm();
    form.setDate(date == null || date.isBefore(reservationSchedule.today())
        ? reservationSchedule.today() : date);
    form.setGuests(2);
    form.setPreferredArea("");
    model.addAttribute("form", form);
    addReservationPageData(model);
    return "web/reservation-form";
  }

  @PostMapping("/reservations")
  public String reserve(
      @Valid @ModelAttribute("form") ReservationForm form,
      BindingResult result,
      HttpSession session,
      Model model,
      RedirectAttributes redirect) {
    if (!result.hasErrors()) {
      try {
        Reservation saved = reservationService.create(form);
        confirmationAccess.grant(session, saved.getCode());
        redirect.addAttribute("code", saved.getCode());
        return "redirect:/reservations/confirmation";
      } catch (BookingException exception) {
        if (exception.getField() == null) {
          result.reject("booking.unavailable", exception.getMessage());
        } else {
          result.rejectValue(exception.getField(), "booking.invalid", exception.getMessage());
        }
      } catch (TransientDataAccessException exception) {
        result.reject("booking.busy", "Hệ thống đang xử lý nhiều yêu cầu. Vui lòng thử lại trong ít giây.");
      }
    }
    addReservationPageData(model);
    return "web/reservation-form";
  }

  @GetMapping("/reservations/confirmation")
  public String confirmation(@RequestParam(required = false) String code,
      HttpServletRequest request, HttpServletResponse response, Model model) {
    response.setHeader("Cache-Control", "no-store, max-age=0");
    response.setHeader("Pragma", "no-cache");
    response.setHeader("Referrer-Policy", "no-referrer");
    Reservation reservation = confirmationAccess.canView(request.getSession(false), code)
        ? reservationService.findByCode(code) : null;
    if (reservation == null) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
    } else {
      model.addAttribute("reservation", reservation);
    }
    return "web/confirmation";
  }

  @GetMapping("/login")
  public String login() {
    return "auth/login";
  }

  @GetMapping("/staff/reservations")
  public String staffReservations(Model model) {
    model.addAttribute("reservations", reservationService.findRecent());
    model.addAttribute("changeRequests", reservationManagement.pendingReviews());
    return "staff/reservations";
  }

  @PostMapping("/staff/reservations/{id}/status")
  public String updateReservationStatus(
      @PathVariable Long id,
      @RequestParam String status,
      RedirectAttributes redirect) {
    try {
      reservationService.updateStatus(id, status);
      redirect.addFlashAttribute("notice", "Đã cập nhật yêu cầu đặt bàn.");
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/staff/reservations";
  }

  @GetMapping("/staff/shift")
  public String staffShift(Authentication authentication, Model model) {
    StaffShift shift = shifts.current(authentication.getName());
    model.addAttribute("shift", shift);
    model.addAttribute("sales", shifts.salesFor(shift));
    boolean admin = authentication.getAuthorities().stream()
        .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    model.addAttribute("recentShifts", shifts.recentShifts(authentication.getName(), admin));
    model.addAttribute("dishes", dishes.findByAvailableTrueOrderByCategoryNameAscNameAsc());
    model.addAttribute("saleForm", new CashSaleForm());
    return "staff/shift";
  }

  @PostMapping("/staff/shift/open")
  public String openShift(
      Authentication authentication,
      @RequestParam BigDecimal openingCash,
      RedirectAttributes redirect) {
    try {
      shifts.open(authentication.getName(), openingCash);
      redirect.addFlashAttribute("notice", "Đã mở ca làm việc.");
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/staff/shift";
  }

  @PostMapping("/staff/shift/sales")
  public String addSale(
      Authentication authentication,
      @Valid @ModelAttribute("saleForm") CashSaleForm saleForm,
      RedirectAttributes redirect) {
    try {
      shifts.addSale(authentication.getName(), saleForm);
      redirect.addFlashAttribute("notice", "Đã ghi nhận món bán trong ca hiện tại.");
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/staff/shift";
  }

  @PostMapping("/staff/shift/close")
  public String closeShift(
      Authentication authentication,
      @RequestParam BigDecimal countedCash,
      RedirectAttributes redirect) {
    try {
      StaffShift closed = shifts.close(authentication.getName(), countedCash);
      redirect.addFlashAttribute(
          "notice",
          "Đã kết ca. Tiền mặt theo sổ: "
              + closed.getOpeningCash().add(closed.getCashSales())
              + " đ; chênh lệch: "
              + closed.getCashDifference()
              + " đ.");
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/staff/shift";
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
