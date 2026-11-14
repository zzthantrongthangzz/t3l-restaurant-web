package t3l.restaurant.t3l_restaurant.controller;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
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
import t3l.restaurant.t3l_restaurant.service.StaffShiftService;

@Controller
public class WebController {
  private final DishRepository dishes;
  private final RestaurantTableRepository restaurantTables;
  private final ReservationService reservationService;
  private final FaqRepository faqs;
  private final StaffShiftService shifts;

  public WebController(
      DishRepository dishes,
      RestaurantTableRepository restaurantTables,
      ReservationService reservationService,
      FaqRepository faqs,
      StaffShiftService shifts) {
    this.dishes = dishes;
    this.restaurantTables = restaurantTables;
    this.reservationService = reservationService;
    this.faqs = faqs;
    this.shifts = shifts;
  }

  @GetMapping("/")
  public String home(Model model) {
    model.addAttribute(
        "dishes",
        dishes.findByAvailableTrueOrderByCategoryNameAscNameAsc().stream().limit(3).toList());
    return "web/home";
  }

  @GetMapping("/menu")
  public String menu(Model model) {
    model.addAttribute("dishes", dishes.findByAvailableTrueOrderByCategoryNameAscNameAsc());
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
      Authentication authentication,
      Model model) {
    csrfToken.getToken();
    ReservationForm form = new ReservationForm();
    form.setDate(date == null ? LocalDate.now() : date);
    form.setTime(LocalTime.of(18, 0));
    model.addAttribute("form", form);
    addReservationPageData(model, form.getDate(), canViewGuestNames(authentication));
    return "web/reservation-form";
  }

  @PostMapping("/reservations")
  public String reserve(
      @Valid @ModelAttribute("form") ReservationForm form,
      BindingResult result,
      Authentication authentication,
      Model model,
      RedirectAttributes redirect) {
    RestaurantTable table = null;
    if (form.getTableId() != null) {
      table = restaurantTables.findById(form.getTableId()).filter(RestaurantTable::isActive).orElse(null);
    }
    if (table == null) {
      result.rejectValue("tableId", "table.invalid", "Bàn đã chọn không hợp lệ.");
    } else {
      if (form.getGuests() != null && form.getGuests() > table.getCapacity()) {
        result.rejectValue(
            "guests", "table.capacity", "Số khách vượt quá sức chứa của bàn đã chọn.");
      }
      if (form.getDate() != null
          && !reservationService.isTableAvailable(table.getId(), form.getDate())) {
        result.rejectValue("tableId", "table.taken", "Bàn đã được đặt trong ngày này.");
      }
    }
    if (result.hasErrors()) {
      addReservationPageData(model, form.getDate(), canViewGuestNames(authentication));
      model.addAttribute("openReservationModal", form.getTableId() != null);
      return "web/reservation-form";
    }
    final Reservation saved;
    try {
      saved = reservationService.create(form, table);
    } catch (IllegalStateException exception) {
      result.rejectValue("tableId", "table.taken", exception.getMessage());
      addReservationPageData(model, form.getDate(), canViewGuestNames(authentication));
      model.addAttribute("openReservationModal", true);
      return "web/reservation-form";
    }
    redirect.addAttribute("code", saved.getCode());
    return "redirect:/reservations/confirmation";
  }

  @GetMapping("/reservations/confirmation")
  public String confirmation(@RequestParam String code, Model model) {
    var reservation = reservationService.findByCode(code);
    if (reservation == null) return "redirect:/reservations/new";
    model.addAttribute("reservation", reservation);
    return "web/confirmation";
  }

  @GetMapping("/login")
  public String login() {
    return "auth/login";
  }

  @GetMapping("/staff/reservations")
  public String staffReservations(Model model) {
    model.addAttribute("reservations", reservationService.findRecent());
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

  private void addReservationPageData(Model model, LocalDate date, boolean showGuestNames) {
    LocalDate selectedDate = date == null ? LocalDate.now() : date;
    model.addAttribute("selectedDate", selectedDate);
    model.addAttribute("showGuestNames", showGuestNames);
    model.addAttribute(
        "tables",
        reservationService.getTableAvailability(selectedDate, showGuestNames));
  }

  private boolean canViewGuestNames(Authentication authentication) {
    return authentication != null
        && authentication.getAuthorities().stream()
            .anyMatch(authority ->
                authority.getAuthority().equals("ROLE_STAFF")
                    || authority.getAuthority().equals("ROLE_ADMIN"));
  }
}
