package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.dto.CashSaleForm;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.dto.ReservationUpdateForm;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;
import t3l.restaurant.t3l_restaurant.entity.TableKind;
import t3l.restaurant.t3l_restaurant.repository.DishRepository;
import t3l.restaurant.t3l_restaurant.repository.RestaurantTableRepository;
import t3l.restaurant.t3l_restaurant.service.BookingException;
import t3l.restaurant.t3l_restaurant.service.DiningOrderService;
import t3l.restaurant.t3l_restaurant.service.ReservationManagementService;
import t3l.restaurant.t3l_restaurant.service.ReservationSchedule;
import t3l.restaurant.t3l_restaurant.service.ReservationService;
import t3l.restaurant.t3l_restaurant.service.StaffShiftService;
import t3l.restaurant.t3l_restaurant.service.StaffWorkspaceService;

@Controller
@RequestMapping({"/staff", "/admin"})
@RequiredArgsConstructor
public class StaffController {
  private static final String RESERVATIONS = "/staff/reservations";
  private static final String TABLES = "/staff/tables";
  private static final String SHIFT = "/staff/shift";
  private static final String ORDERS = "/staff/orders";

  private final StaffWorkspaceService workspace;
  private final ReservationService bookings;
  private final ReservationManagementService changes;
  private final RestaurantTableRepository tables;
  private final DiningOrderService orders;
  private final StaffShiftService shifts;
  private final DishRepository dishes;
  private final ReservationSchedule schedule;
  private final HttpServletRequest request;

  @ModelAttribute("opsBase")
  String operationsBase() {
    return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/admin/") ? "/admin" : "/staff";
  }

  @GetMapping("/reservations")
  public String reservations(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String name,
      @RequestParam(defaultValue = "") String phone, @RequestParam(defaultValue = "") String code,
      @RequestParam(defaultValue = "0") int page, Model model) {
    var results = workspace.search(date, status, name.strip(), phone.strip(), code.strip(), page);
    var labels = new LinkedHashMap<String, String>();
    StaffWorkspaceService.STATUSES.forEach(value -> labels.put(value, Reservation.labelFor(value)));
    model.addAttribute("results", results);
    model.addAttribute("date", date);
    model.addAttribute("status", status);
    model.addAttribute("name", name);
    model.addAttribute("phone", phone);
    model.addAttribute("code", code);
    model.addAttribute("statuses", labels);
    model.addAttribute("changeRequests", changes.pendingReviews());
    return "staff/reservations";
  }

  @GetMapping("/reservations/new")
  public String newBooking(@RequestParam(required = false) Long tableId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(defaultValue = "false") boolean walkIn, Model model) {
    var form = new ReservationForm();
    form.setDate(date == null || date.isBefore(schedule.today()) ? schedule.today() : date);
    form.setGuests(2);
    model.addAttribute("bookingForm", form);
    return newBookingPage(tableId, walkIn, model);
  }

  @PostMapping("/reservations")
  public String createBooking(@ModelAttribute("bookingForm") ReservationForm form, BindingResult errors,
      @RequestParam(required = false) Long tableId, @RequestParam(defaultValue = "false") boolean walkIn,
      Authentication actor, Model model, RedirectAttributes redirect) {
    if (!errors.hasErrors()) {
      try {
        var booking = bookings.createStaffBooking(form, tableId, walkIn, actor.getName());
        redirect.addFlashAttribute("notice", walkIn ? "Đã đặt bàn ngay và bắt đầu phục vụ." : "Đã ghi nhận và xác nhận đặt bàn chờ.");
        return "redirect:" + operationsPath(RESERVATIONS + "/" + booking.getId());
      } catch (BookingException exception) {
        String field = exception.getField();
        if (field != null && !"tableId".equals(field)) errors.rejectValue(field, "booking.invalid", exception.getMessage());
        else errors.reject("booking.invalid", exception.getMessage());
      } catch (IllegalStateException exception) {
        errors.reject("booking.shift", exception.getMessage());
      } catch (TransientDataAccessException exception) {
        errors.reject("booking.busy", "Bàn đang được cập nhật. Vui lòng thử lại.");
      }
    }
    return newBookingPage(tableId, walkIn, model);
  }

  private String newBookingPage(Long tableId, boolean walkIn, Model model) {
    bookingOptions(model);
    model.addAttribute("selectedTable", tableId);
    model.addAttribute("walkIn", walkIn);
    return "staff/reservation-new";
  }

  @GetMapping("/reservations/{id}")
  public String reservation(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean move, Model model) {
    var view = requireBooking(id);
    var booking = view.booking();
    model.addAttribute("booking", booking);
    var actions = workspace.actions(booking);
    model.addAttribute("actions", actions);
    model.addAttribute("moveOptions", actions.moveTable() ? bookings.moveOptions(booking) : java.util.List.of());
    model.addAttribute("openMove", move);
    model.addAttribute("serviceSeconds", booking.getSeatedAt() == null ? 0
        : Math.max(0, java.time.Duration.between(booking.getSeatedAt(),
            booking.getCompletedAt() == null ? LocalDateTime.now(schedule.getClock()) : booking.getCompletedAt()).getSeconds()));
    model.addAttribute("order", view.order());
    model.addAttribute("canAddMenuItems", bookings.canAddMenuItems(booking, view.order(), true));
    model.addAttribute("history", workspace.history(id));
    model.addAttribute("changes", changes.history(id));
    bookingOptions(model);
    return "staff/reservation-detail";
  }

  @PostMapping("/reservations/{id}/status")
  public String status(@PathVariable Long id, @RequestParam String status,
      @RequestParam(defaultValue = "") String from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnDate,
      Authentication actor, RedirectAttributes redirect) {
    String target = switch (from) {
      case "detail" -> RESERVATIONS + "/" + id;
      case "table" -> TABLES;
      case "order" -> ORDERS + "/" + id;
      default -> RESERVATIONS;
    };
    if ("table".equals(from) && returnDate != null) redirect.addAttribute("date", returnDate);
    return action(redirect, target, "Đã cập nhật lượt đặt bàn.", () -> bookings.staffStatus(id, status, actor.getName()));
  }

  @PostMapping("/reservations/{id}/deposit")
  public String verifyDeposit(@PathVariable Long id, @RequestParam boolean received,
      Authentication actor, RedirectAttributes redirect) {
    return action(redirect, RESERVATIONS + "/" + id,
        received ? "Đã xác nhận nhận tiền cọc." : "Đã từ chối mã giao dịch cọc.",
        () -> bookings.verifyDeposit(id, received, actor.getName()));
  }

  @PostMapping("/reservations/{id}/table")
  public String moveTable(@PathVariable Long id, @RequestParam Long tableId,
      Authentication actor, RedirectAttributes redirect) {
    return action(redirect, RESERVATIONS + "/" + id, "Đã chuyển bàn.",
        () -> bookings.moveTable(id, tableId, actor.getName()));
  }

  @PostMapping("/reservations/{id}/reschedule")
  public String reschedule(@PathVariable Long id, @Valid @ModelAttribute ReservationUpdateForm form,
      BindingResult errors, @RequestParam(required = false) Long tableId,
      Authentication actor, RedirectAttributes redirect) {
    if (errors.hasErrors()) return invalid(redirect, RESERVATIONS + "/" + id, errors);
    return action(redirect, RESERVATIONS + "/" + id, "Đã cập nhật lịch đặt bàn.",
        () -> bookings.rescheduleStaffBooking(id, form, tableId, actor.getName()));
  }

  @GetMapping("/tables")
  public String tables(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      Model model) {
    LocalDate selectedDate = date == null ? schedule.today() : date;
    model.addAttribute("board", workspace.board(selectedDate));
    model.addAttribute("tableKinds", TableKind.values());
    model.addAttribute("selectedDate", selectedDate);
    model.addAttribute("today", schedule.today());
    return "staff/tables";
  }

  @PostMapping("/tables/{id}/availability")
  public String tableAvailability(@PathVariable Long id, @RequestParam boolean active,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnDate,
      RedirectAttributes redirect) {
    if (returnDate != null) redirect.addAttribute("date", returnDate);
    return action(redirect, TABLES, active ? "Đã mở lại bàn." : "Đã tạm ngưng sử dụng bàn.",
        () -> bookings.setTableActive(id, active));
  }

  @GetMapping("/orders")
  public String orders(Model model) {
    model.addAttribute("serving", orders.servingTables());
    return "staff/orders";
  }

  @GetMapping("/orders/{id}")
  public String order(@PathVariable Long id, Model model) {
    var view = requireBooking(id);
    model.addAttribute("view", view);
    model.addAttribute("dishes", dishes.findByAvailableTrueOrderByCategoryNameAscNameAsc());
    return "staff/order";
  }

  @PostMapping("/orders/{id}/open")
  public String openOrder(@PathVariable Long id, Authentication actor, RedirectAttributes redirect) {
    return action(redirect, ORDERS + "/" + id, "Đã mở đơn gọi món.", () -> orders.open(id, actor.getName()));
  }

  @PostMapping("/orders/{id}/items")
  public String addItem(@PathVariable Long id, @RequestParam Long dishId, @RequestParam int quantity, RedirectAttributes redirect) {
    return action(redirect, ORDERS + "/" + id, "Đã thêm món.", () -> orders.addItem(id, dishId, quantity));
  }

  @PostMapping("/orders/{id}/items/{itemId}")
  public String updateItem(@PathVariable Long id, @PathVariable Long itemId,
      @RequestParam(required = false) Integer quantity, @RequestParam(defaultValue = "false") boolean cancel,
      RedirectAttributes redirect) {
    return action(redirect, ORDERS + "/" + id, cancel ? "Đã hủy món." : "Đã cập nhật số lượng.",
        () -> orders.adjustItem(id, itemId, quantity, cancel));
  }

  @PostMapping("/orders/{id}/finalize")
  public String finalizeOrder(@PathVariable Long id, RedirectAttributes redirect) {
    return action(redirect, ORDERS + "/" + id, "Đã chốt hóa đơn.", () -> orders.finalizeOrder(id));
  }

  @PostMapping("/orders/{id}/pay")
  public String pay(@PathVariable Long id, @RequestParam String method, @RequestParam(required = false) String reference,
      @RequestParam(defaultValue = "false") boolean received, Authentication actor, RedirectAttributes redirect) {
    return action(redirect, ORDERS + "/" + id, "Đã xác nhận thanh toán và kết thúc phục vụ. Bàn đã được giải phóng.",
        () -> orders.collect(id, method, reference, received, actor.getName()));
  }

  @GetMapping("/shift")
  public String shift(@RequestParam(required = false) Long id, Authentication actor, Model model) {
    boolean admin = actor.getAuthorities().stream().anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    var view = shifts.overview(actor.getName(), id, admin);
    if (id != null && view.selected() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    model.addAttribute("view", view);
    model.addAttribute("dishes", dishes.findByAvailableTrueOrderByCategoryNameAscNameAsc());
    model.addAttribute("saleForm", new CashSaleForm());
    return "staff/shift";
  }

  @PostMapping("/shift/open")
  public String openShift(@RequestParam BigDecimal openingCash, Authentication actor, RedirectAttributes redirect) {
    return action(redirect, SHIFT, "Đã mở ca làm việc.", () -> shifts.open(actor.getName(), openingCash));
  }

  @PostMapping("/shift/sales")
  public String addSale(@Valid @ModelAttribute("saleForm") CashSaleForm form, BindingResult errors,
      @RequestParam(defaultValue = "false") boolean received, Authentication actor, RedirectAttributes redirect) {
    if (errors.hasErrors()) return invalid(redirect, SHIFT, errors);
    return action(redirect, SHIFT, "Đã ghi nhận khoản bán lẻ.", () -> {
      if (!received) throw new BookingException(null, "Hãy xác nhận đã thực nhận tiền trước khi ghi nhận khoản bán lẻ.");
      shifts.addSale(actor.getName(), form);
    });
  }

  @PostMapping("/shift/close")
  public String closeShift(@RequestParam BigDecimal countedCash, Authentication actor, RedirectAttributes redirect) {
    return action(redirect, SHIFT, "Đã kết ca và lưu đối soát tiền mặt.", () -> {
      var closed = shifts.close(actor.getName(), countedCash);
      redirect.addAttribute("id", closed.getId());
    });
  }

  private DiningOrderService.OrderView requireBooking(Long id) {
    var view = orders.view(id);
    if (view == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return view;
  }

  private void bookingOptions(Model model) {
    var activeTables = tables.findByActiveTrueOrderByCodeAsc();
    model.addAttribute("tableOptions", activeTables);
    model.addAttribute("timeSlots", schedule.getTimeSlots());
    model.addAttribute("today", schedule.today());
  }

  private String invalid(RedirectAttributes redirect, String path, BindingResult errors) {
    redirect.addFlashAttribute("error", errors.getAllErrors().getFirst().getDefaultMessage());
    return "redirect:" + operationsPath(path);
  }

  private String action(RedirectAttributes redirect, String path, String notice, Runnable operation) {
    try {
      operation.run();
      redirect.addFlashAttribute("notice", notice);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    } catch (DataIntegrityViolationException exception) {
      redirect.addFlashAttribute("error", "Dữ liệu đã được ghi nhận hoặc đang trùng. Kiểm tra lại trước khi thao tác.");
    } catch (TransientDataAccessException exception) {
      redirect.addFlashAttribute("error", "Dữ liệu đang được cập nhật. Vui lòng tải lại và thử lại.");
    }
    return "redirect:" + operationsPath(path);
  }

  private String operationsPath(String path) { return operationsBase() + path.substring("/staff".length()); }
}
