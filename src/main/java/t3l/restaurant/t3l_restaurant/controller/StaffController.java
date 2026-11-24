package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import t3l.restaurant.t3l_restaurant.repository.DishRepository;
import t3l.restaurant.t3l_restaurant.repository.RestaurantTableRepository;
import t3l.restaurant.t3l_restaurant.service.BookingException;
import t3l.restaurant.t3l_restaurant.service.DiningOrderService;
import t3l.restaurant.t3l_restaurant.service.ReservationManagementService;
import t3l.restaurant.t3l_restaurant.service.ReservationSchedule;
import t3l.restaurant.t3l_restaurant.service.ReservationService;
import t3l.restaurant.t3l_restaurant.service.StaffShiftService;
import t3l.restaurant.t3l_restaurant.service.StaffWorkspaceService;
import t3l.restaurant.t3l_restaurant.service.PaymentIntentService;
import t3l.restaurant.t3l_restaurant.service.PaymentVerificationService;
import t3l.restaurant.t3l_restaurant.service.SepayProperties;

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
  private final t3l.restaurant.t3l_restaurant.service.PreorderService preorders;
  private final HttpServletRequest request;
  private final PaymentIntentService paymentIntents;
  private final PaymentVerificationService paymentVerification;
  private final SepayProperties sepay;

  @ModelAttribute("opsBase")
  String operationsBase() {
    return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/admin/") ? "/admin" : "/staff";
  }

  @GetMapping("/reservations")
  public String reservations(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page, Authentication actor, Model model) {
    var results = workspace.searchQuick(date, status, q, page);
    boolean shiftOpen = shifts.current(actor.getName()) != null;
    var bookingActions = results.getContent().stream().collect(java.util.stream.Collectors.toMap(
        Reservation::getId, booking -> workspace.actions(booking, shiftOpen)));
    var labels = new LinkedHashMap<String, String>();
    StaffWorkspaceService.STATUSES.forEach(value -> labels.put(value, Reservation.labelFor(value)));
    model.addAttribute("results", results);
    model.addAttribute("date", date);
    model.addAttribute("status", status);
    model.addAttribute("q", q);
    model.addAttribute("bookingActions", bookingActions);
    model.addAttribute("statuses", labels);
    model.addAttribute("changeRequests", changes.pendingReviews());
    return "staff/reservations";
  }

  @GetMapping("/reservations/new")
  public String newBooking(@RequestParam(required = false) Long tableId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time,
      @RequestParam(defaultValue = "2") int guests,
      @RequestParam(defaultValue = "false") boolean walkIn, Model model) {
    var form = new ReservationForm();
    form.setDate(date == null ? schedule.today() : date);
    form.setTime(time);
    form.setGuests(Math.clamp(guests, 1, 20));
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
        redirect.addFlashAttribute("notice", walkIn ? "Đã tiếp nhận khách. Chọn Nhận bàn để bắt đầu phục vụ." : "Đã tạo đặt bàn và xác nhận lịch.");
        return "redirect:" + operationsPath(RESERVATIONS + "/" + booking.getId());
      } catch (BookingException exception) {
        String field = exception.getField();
        if (field != null && !"tableId".equals(field)) errors.rejectValue(field, "booking.invalid", exception.getMessage());
        else errors.reject("booking.invalid", exception.getMessage());
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
  public String reservation(@PathVariable Long id, Authentication actor, Model model) {
    var view = requireBooking(id);
    var booking = view.booking();
    model.addAttribute("booking", booking);
    model.addAttribute("actions", workspace.actions(booking, shifts.current(actor.getName()) != null));
    model.addAttribute("order", view.order());
    model.addAttribute("depositCheckout", paymentIntents.depositCheckout(booking.getId()));
    model.addAttribute("depositPaymentStatus", paymentIntents.depositStatus(booking));
    model.addAttribute("unavailablePreorders", booking.isOpenBooking() ? preorders.unavailable(view.order()) : java.util.Set.of());
    model.addAttribute("history", workspace.history(id));
    model.addAttribute("changes", changes.history(id));
    bookingOptions(model);
    return "staff/reservation-detail";
  }

  @PostMapping("/reservations/{id}/status")
  public String status(@PathVariable Long id, @RequestParam String status,
      @RequestParam(defaultValue = "") String from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime returnTime,
      @RequestParam(required = false) Integer returnGuests, Authentication actor, RedirectAttributes redirect) {
    if (Reservation.SEATED.equals(status)) {
      return action(redirect, ORDERS + "/" + id, "Đã nhận khách và bàn. Đơn gọi món đã sẵn sàng.", () -> {
        bookings.staffStatus(id, status, actor.getName());
        orders.open(id, actor.getName());
      });
    }
    String target = switch (from) {
      case "detail" -> RESERVATIONS + "/" + id;
      case "table" -> TABLES;
      case "order" -> ORDERS + "/" + id;
      default -> RESERVATIONS;
    };
    if (TABLES.equals(target)) keepBoardFilters(redirect, returnDate, returnTime, returnGuests);
    return action(redirect, target, "Đã cập nhật lượt đặt bàn.", () -> bookings.staffStatus(id, status, actor.getName()));
  }

  @GetMapping("/reservations/{id}/payment-status")
  @ResponseBody
  public PaymentIntentService.PaymentStatus reservationPaymentStatus(@PathVariable Long id, Authentication actor) {
    var view = requireBooking(id);
    return paymentIntents.depositStatus(view.booking(), shifts.current(actor.getName()) != null);
  }

  @PostMapping("/reservations/{id}/deposit")
  public String verifyDeposit(@PathVariable Long id, @RequestParam boolean received,
      @RequestParam(required = false) BigDecimal amount, @RequestParam String reference,
      Authentication actor, RedirectAttributes redirect) {
    return action(redirect, RESERVATIONS + "/" + id,
        received ? "Đã xác nhận nhận tiền cọc." : "Đã từ chối mã giao dịch cọc.",
        () -> bookings.verifyDeposit(id, received, amount, reference, actor.getName()));
  }

  @PostMapping("/reservations/{id}/deposit/refund")
  public String refundDeposit(@PathVariable Long id, @RequestParam BigDecimal amount, @RequestParam String reference,
      Authentication actor, RedirectAttributes redirect) {
    return action(redirect, RESERVATIONS + "/" + id, "Đã ghi nhận hoàn tiền cọc.",
        () -> bookings.refundDeposit(id, amount, reference, actor.getName()));
  }

  @PostMapping("/reservations/{id}/preorder/unavailable")
  public String removeUnavailablePreorder(@PathVariable Long id, Authentication actor, RedirectAttributes redirect) {
    return action(redirect, RESERVATIONS + "/" + id, "Đã bỏ món không còn phục vụ và cập nhật tiền cọc.",
        () -> bookings.removeUnavailablePreorders(id, actor.getName()));
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
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time,
      @RequestParam(defaultValue = "1") int guests, Authentication actor, Model model) {
    var today = schedule.today();
    var rules = schedule.rules();
    var slots = rules.slots();
    LocalDate selectedDate = date == null ? today : date;
    LocalTime selectedTime = time != null && slots.contains(time) ? time
        : selectedDate.equals(today) ? null : slots.getFirst();
    int selectedGuests = Math.clamp(guests, 1, 20);
    var areas = workspace.board(selectedDate, selectedTime, selectedGuests, rules.duration());
    boolean shiftOpen = shifts.current(actor.getName()) != null;
    var bookingActions = areas.values().stream().flatMap(java.util.Collection::stream).map(item -> item.current())
        .filter(java.util.Objects::nonNull).distinct().collect(java.util.stream.Collectors.toMap(
            Reservation::getId, booking -> workspace.actions(booking, shiftOpen)));
    model.addAttribute("areas", areas);
    model.addAttribute("bookingActions", bookingActions);
    model.addAttribute("selectedDate", selectedDate);
    model.addAttribute("selectedTime", selectedTime);
    model.addAttribute("selectedGuests", selectedGuests);
    model.addAttribute("timeSlots", slots);
    model.addAttribute("duration", rules.duration());
    model.addAttribute("today", today);
    return "staff/tables";
  }

  @PostMapping("/tables/{id}/availability")
  public String tableAvailability(@PathVariable Long id, @RequestParam boolean active,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime returnTime,
      @RequestParam(required = false) Integer returnGuests, RedirectAttributes redirect) {
    keepBoardFilters(redirect, returnDate, returnTime, returnGuests);
    return action(redirect, TABLES, active ? "Đã mở lại bàn." : "Đã tạm ngưng sử dụng bàn.",
        () -> bookings.setTableActive(id, active));
  }

  private void keepBoardFilters(RedirectAttributes redirect, LocalDate date, LocalTime time, Integer guests) {
    if (date != null) redirect.addAttribute("date", date);
    if (time != null) redirect.addAttribute("time", time.toString());
    if (guests != null) redirect.addAttribute("guests", guests);
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
    var checkout = paymentIntents.invoiceCheckout(id);
    model.addAttribute("invoiceCheckout", checkout);
    model.addAttribute("invoicePaymentStatus", paymentIntents.intentStatus(checkout == null ? null : checkout.intent()));
    model.addAttribute("demoEnabled", sepay.demoAllowed());
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
  public String pay(@PathVariable Long id, @RequestParam String method, @RequestParam(required = false) String paymentCode,
      @RequestParam(defaultValue = "false") boolean received, Authentication actor, RedirectAttributes redirect) {
    if ("TRANSFER".equals(method)) {
      return action(redirect, ORDERS + "/" + id, "Đã xác nhận thanh toán chuyển khoản.",
          () -> orders.collectVerifiedTransfer(id, paymentCode, actor.getName()));
    }
    return action(redirect, ORDERS + "/" + id, "Đã xác nhận thanh toán.",
        () -> orders.collect(id, method, null, received, actor.getName()));
  }

  @PostMapping("/orders/{id}/payment-intent")
  public String createInvoicePayment(@PathVariable Long id, Authentication actor, RedirectAttributes redirect) {
    return action(redirect, ORDERS + "/" + id, "Đã tạo QR cho số tiền còn phải thanh toán.",
        () -> paymentIntents.ensureInvoiceIntent(id, actor.getName()));
  }

  @PostMapping("/orders/{id}/payment-demo")
  public String demoInvoicePayment(@PathVariable Long id, Authentication actor, RedirectAttributes redirect) {
    return action(redirect, ORDERS + "/" + id, "Đã xác minh khoản thanh toán hóa đơn DEMO.", () -> {
      var checkout = paymentIntents.invoiceCheckout(id);
      if (checkout == null) throw new BookingException(null, "Hãy tạo mã QR trước khi giả lập thanh toán.");
      paymentVerification.simulate(checkout.intent().getPaymentCode());
    });
  }

  @GetMapping("/orders/{id}/payment-status")
  @ResponseBody
  public PaymentIntentService.PaymentStatus invoicePaymentStatus(@PathVariable Long id) {
    var checkout = paymentIntents.invoiceCheckout(id);
    return paymentIntents.intentStatus(checkout == null ? null : checkout.intent());
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
    model.addAttribute("areaOptions", activeTables.stream().map(RestaurantTable::getTableType).distinct().sorted().toList());
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
