package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
import t3l.restaurant.t3l_restaurant.entity.TableKind;
import t3l.restaurant.t3l_restaurant.repository.DishRepository;
import t3l.restaurant.t3l_restaurant.repository.RestaurantTableRepository;
import t3l.restaurant.t3l_restaurant.service.BookingException;
import t3l.restaurant.t3l_restaurant.service.BookingCustomerService;
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
  private final BookingCustomerService bookingCustomers;
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
      @RequestParam(defaultValue = "") String scope, @RequestParam(defaultValue = "0") int page,
      Authentication actor, Model model) {
    boolean staffList = "/staff".equals(operationsBase());
    String selectedScope = staffList && ("upcoming".equals(scope) || "attention".equals(scope)) ? scope : "";
    String selectedStatus = StaffWorkspaceService.STATUSES.contains(status) ? status : "";
    var now = LocalDateTime.now(schedule.getClock());
    var today = now.toLocalDate();
    var results = workspace.searchQuick(date, selectedStatus, q, page, selectedScope, staffList, now);
    boolean shiftOpen = shifts.current(actor.getName()) != null;
    var pendingReviews = changes.pendingReviews();
    if (staffList) {
      model.addAttribute("reservationRows", workspace.reservationRows(results.getContent(), shiftOpen, now));
      model.addAttribute("reviewByBooking", pendingReviews.stream().collect(Collectors.toMap(
          item -> item.request().getReservation().getId(), item -> item.request().getId(), (first, next) -> first)));
      String quick = selectedScope;
      if (quick.isEmpty() && selectedStatus.isEmpty()) {
        quick = date == null ? "all" : today.equals(date) ? "today" : "";
      } else if (quick.isEmpty() && date == null && Reservation.PENDING.equals(selectedStatus)) {
        quick = "pending";
      }
      model.addAttribute("quick", quick);
      model.addAttribute("today", today);
      model.addAttribute("scope", selectedScope);
    } else {
      model.addAttribute("bookingActions", results.getContent().stream().collect(Collectors.toMap(
          Reservation::getId, booking -> workspace.actions(booking, shiftOpen))));
    }
    var labels = new LinkedHashMap<String, String>();
    StaffWorkspaceService.STATUSES.forEach(value -> labels.put(value, Reservation.labelFor(value)));
    model.addAttribute("results", results);
    model.addAttribute("date", date);
    model.addAttribute("status", selectedStatus);
    model.addAttribute("q", q);
    model.addAttribute("statuses", labels);
    model.addAttribute("changeRequests", pendingReviews);
    return "staff/reservations";
  }

  @GetMapping("/reservations/list-status")
  @ResponseBody
  public Map<Long, StaffWorkspaceService.ReservationRow> reservationListStatus(@RequestParam List<Long> ids,
      Authentication actor) {
    boolean shiftOpen = shifts.current(actor.getName()) != null;
    return workspace.reservationRowUpdates(ids, shiftOpen, LocalDateTime.now(schedule.getClock()));
  }

  @GetMapping("/notifications/pending-bookings")
  @ResponseBody
  public PendingBookingCount pendingBookingCount() {
    return new PendingBookingCount(workspace.pendingBookingCount());
  }

  public record PendingBookingCount(long count) {}

  @GetMapping("/reservations/new")
  public String newBooking(@RequestParam(required = false) Long tableId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time,
      @RequestParam(defaultValue = "2") int guests,
      @RequestParam(defaultValue = "false") boolean walkIn, @RequestParam(defaultValue = "false") boolean popup,
      Authentication actor, Model model) {
    return bookingStart(tableId, date, time, guests, walkIn, popup, actor, model);
  }

  @PostMapping("/reservations/customer/lookup")
  public String lookupBookingCustomer(@RequestParam String phone, @RequestParam(required = false) Long tableId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time,
      @RequestParam(defaultValue = "2") int guests, @RequestParam(defaultValue = "false") boolean walkIn,
      @RequestParam(defaultValue = "false") boolean popup, Authentication actor, Model model, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    try {
      String normalized = bookingCustomers.normalizePhone(phone);
      model.addAttribute("bookingCustomer", bookingCustomers.find(normalized));
      model.addAttribute("customerPhone", normalized);
    } catch (IllegalArgumentException exception) {
      model.addAttribute("lookupError", exception.getMessage());
      model.addAttribute("lookupInput", phone);
    }
    return bookingStart(tableId, date, time, guests, walkIn, popup, actor, model);
  }

  @PostMapping("/reservations/customer")
  public String saveBookingCustomer(@RequestParam String phone, @RequestParam String fullName,
      @RequestParam(defaultValue = "") String email, @RequestParam(required = false) Long tableId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time,
      @RequestParam(defaultValue = "2") int guests, @RequestParam(defaultValue = "false") boolean walkIn,
      @RequestParam(defaultValue = "false") boolean popup, Authentication actor, Model model, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    try {
      model.addAttribute("bookingCustomer", bookingCustomers.save(phone, fullName, email));
    } catch (IllegalArgumentException exception) {
      model.addAttribute("customerPhone", phone);
      model.addAttribute("customerError", exception.getMessage());
      model.addAttribute("draftName", fullName);
      model.addAttribute("draftEmail", email);
      try {
        model.addAttribute("bookingCustomer", bookingCustomers.find(phone));
      } catch (IllegalArgumentException ignored) {
        model.addAttribute("lookupInput", phone);
        model.addAttribute("lookupError", exception.getMessage());
        model.addAttribute("customerPhone", null);
      }
    }
    return bookingStart(tableId, date, time, guests, walkIn, popup, actor, model);
  }

  private String bookingStart(Long tableId, LocalDate date, LocalTime time, int guests,
      boolean walkIn, boolean popup, Authentication actor, Model model) {
    var form = new ReservationForm();
    form.setDate(date == null ? schedule.today() : date);
    form.setTime(time);
    form.setGuests(Math.clamp(guests, 1, TableKind.MAX_CAPACITY));
    model.addAttribute("bookingForm", form);
    return newBookingPage(tableId, walkIn, popup, actor, model);
  }

  @PostMapping("/reservations")
  public String createBooking(@ModelAttribute("bookingForm") ReservationForm form, BindingResult errors,
      @RequestParam(required = false) Long tableId, @RequestParam(defaultValue = "false") boolean walkIn,
      @RequestParam(required = false) Long customerId, @RequestParam(defaultValue = "false") boolean popup,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime returnTime,
      @RequestParam(required = false) Integer returnGuests,
      Authentication actor, Model model, RedirectAttributes redirect, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    try {
      var customer = bookingCustomers.requireAccount(customerId);
      model.addAttribute("bookingCustomer", new BookingCustomerService.Contact(customer.getId(),
          customer.getFullName(), customer.getEmail(), customer.getPhone()));
      if (!errors.hasErrors()) {
        var booking = bookings.createStaffBooking(form, tableId, walkIn, actor.getName(), customer);
        redirect.addFlashAttribute("notice", walkIn ? "Đã đặt bàn ngay và bắt đầu phục vụ." : "Đã ghi nhận và xác nhận đặt bàn chờ.");
        if (popup) {
          keepBoardFilters(redirect, returnDate, returnTime, returnGuests);
          return "redirect:" + operationsPath(TABLES);
        }
        return "redirect:" + operationsPath(RESERVATIONS + "/" + booking.getId());
      }
    } catch (BookingException exception) {
      errors.reject("booking.invalid", exception.getMessage());
      if (!model.containsAttribute("bookingCustomer")) model.addAttribute("lookupError", exception.getMessage());
    } catch (IllegalStateException exception) {
      errors.reject("booking.shift", exception.getMessage());
    } catch (TransientDataAccessException exception) {
      errors.reject("booking.busy", "Bàn đang được cập nhật. Vui lòng thử lại.");
    }
    if (popup) response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
    return newBookingPage(tableId, walkIn, popup, actor, model);
  }

  private String newBookingPage(Long tableId, boolean walkIn, boolean popup, Authentication actor, Model model) {
    if (popup && tableId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    model.addAttribute("selectedTable", tableId);
    model.addAttribute("walkIn", walkIn);
    model.addAttribute("popup", popup);
    model.addAttribute("shiftRequired", walkIn && shifts.current(actor.getName()) == null);
    if (tableId != null) model.addAttribute("selectedTableName", tables.findById(tableId)
        .map(RestaurantTable::getCode).orElse("Bàn đã chọn"));
    bookingOptions(model);
    return popup ? "staff/fragments/booking-form :: flow" : "staff/reservation-new";
  }

  @GetMapping("/reservations/{id}")
  public String reservation(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean move, Authentication actor, Model model) {
    var view = requireBooking(id);
    var booking = view.booking();
    model.addAttribute("booking", booking);
    var actions = workspace.actions(booking, shifts.current(actor.getName()) != null);
    model.addAttribute("actions", actions);
    model.addAttribute("moveOptions", actions.moveTable() ? bookings.moveOptions(booking) : java.util.List.of());
    model.addAttribute("openMove", move);
    model.addAttribute("serviceSeconds", booking.getSeatedAt() == null ? 0
        : Math.max(0, java.time.Duration.between(booking.getSeatedAt(),
            booking.getCompletedAt() == null ? LocalDateTime.now(schedule.getClock()) : booking.getCompletedAt()).getSeconds()));
    model.addAttribute("order", view.order());
    model.addAttribute("depositPaymentStatus", paymentIntents.depositStatus(booking));
    model.addAttribute("depositCheckout", booking.isDepositOutstanding() ? paymentIntents.depositCheckout(id) : null);
    model.addAttribute("manualDeposit", !paymentIntents.usesQr() && "CONFIRMED".equals(booking.getStatus())
        && "WAITING_PAYMENT".equals(booking.getDepositStatus()) && !booking.hasExpiredDeposit(LocalDateTime.now(schedule.getClock())));
    model.addAttribute("unavailablePreorders", booking.isOpenBooking() ? preorders.unavailable(view.order()) : java.util.Set.of());
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
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime returnTime,
      @RequestParam(required = false) Integer returnGuests,
      Authentication actor, RedirectAttributes redirect) {
    String target = switch (from) {
      case "detail" -> RESERVATIONS + "/" + id;
      case "table" -> TABLES;
      case "order" -> ORDERS + "/" + id;
      default -> RESERVATIONS;
    };
    if ("table".equals(from)) keepBoardFilters(redirect, returnDate, returnTime, returnGuests);
    return action(redirect, target, "Đã cập nhật lượt đặt bàn.", () -> bookings.staffStatus(id, status, actor.getName()));
  }

  @GetMapping("/reservations/{id}/payment-status")
  @ResponseBody
  public PaymentIntentService.PaymentStatus reservationPaymentStatus(@PathVariable Long id, Authentication actor) {
    var booking = requireBooking(id).booking();
    var payment = paymentIntents.depositStatus(booking);
    var actions = workspace.actions(booking, shifts.current(actor.getName()) != null);
    return new PaymentIntentService.PaymentStatus(payment.status(), payment.label(),
        payment.confirmEnabled(), payment.remainingSeconds(), actions.receiveEnabled(), actions.receiveReason());
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

  @PostMapping("/reservations/{id}/deposit/record")
  public String recordDeposit(@PathVariable Long id, @RequestParam BigDecimal amount, @RequestParam String reference,
      Authentication actor, RedirectAttributes redirect) {
    return action(redirect, RESERVATIONS + "/" + id, "Đã ghi nhận tiền cọc.",
        () -> bookings.recordStaffDeposit(id, amount, reference, actor.getName()));
  }

  @PostMapping("/reservations/{id}/preorder/unavailable")
  public String removeUnavailablePreorder(@PathVariable Long id, Authentication actor, RedirectAttributes redirect) {
    return action(redirect, RESERVATIONS + "/" + id, "Đã bỏ món không còn phục vụ và cập nhật tiền cọc.",
        () -> bookings.removeUnavailablePreorders(id, actor.getName()));
  }

  @PostMapping("/reservations/{id}/table")
  public String moveTable(@PathVariable Long id, @RequestParam Long tableId,
      @RequestParam(defaultValue = "false") boolean fromBoard,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime returnTime,
      @RequestParam(required = false) Integer returnGuests,
      Authentication actor, RedirectAttributes redirect) {
    if (fromBoard) keepBoardFilters(redirect, returnDate, returnTime, returnGuests);
    return action(redirect, fromBoard ? TABLES : RESERVATIONS + "/" + id, "Đã chuyển bàn.",
        () -> bookings.moveTable(id, tableId, actor.getName()));
  }

  @GetMapping("/reservations/{id}/table")
  public String moveTableOptions(@PathVariable Long id, Authentication actor, Model model) {
    var booking = requireBooking(id).booking();
    if (!workspace.actions(booking, shifts.current(actor.getName()) != null).moveTable()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }
    model.addAttribute("booking", booking);
    model.addAttribute("moveOptions", bookings.moveOptions(booking));
    return "staff/fragments/move-table :: options(fromBoard=true)";
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
    LocalDate today = schedule.today();
    LocalDate selectedDate = date == null ? today : date;
    var rules = schedule.rules();
    var slots = rules.slots();
    LocalTime selectedTime = time != null && slots.contains(time) ? time : null;
    int selectedGuests = Math.clamp(guests, 1, TableKind.MAX_CAPACITY);
    boolean shiftOpen = shifts.current(actor.getName()) != null;
    boolean adminBoard = "/admin".equals(operationsBase());
    var board = workspace.board(selectedDate, selectedTime, selectedGuests, shiftOpen);
    var kinds = board.stream().map(item -> item.table().getKind()).distinct().toList();
    if (adminBoard) {
      board = board.stream().filter(item -> item.table().getCapacity() >= selectedGuests).toList();
      var counts = board.stream().collect(Collectors.groupingBy(
          StaffWorkspaceService.TableView::getSummaryCategory, Collectors.counting()));
      counts.put("WAITING", board.stream().filter(StaffWorkspaceService.TableView::hasWaitingBooking).count());
      model.addAttribute("boardCounts", counts);
    }
    var groups = board.stream().collect(Collectors.groupingBy(
        item -> item.table().getKind(), LinkedHashMap::new, Collectors.toList()));
    model.addAttribute("shiftOpen", shiftOpen);
    model.addAttribute("board", board);
    model.addAttribute("boardGroups", groups.values());
    model.addAttribute("tableKinds", kinds);
    model.addAttribute("selectedDate", selectedDate);
    model.addAttribute("selectedTime", selectedTime);
    model.addAttribute("selectedEnd", selectedTime == null ? null : selectedTime.plusMinutes(rules.duration()));
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
    if (date != null) redirect.addAttribute("date", date.toString());
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
    model.addAttribute("qrPayments", paymentIntents.usesQr());
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
        () -> {
          if (orders.adjustItem(id,itemId,quantity,cancel))
            redirect.addFlashAttribute("voucherNotice","Voucher đã được gỡ vì giỏ món không còn đạt điều kiện.");
        });
  }

  @PostMapping("/orders/{id}/voucher")
  public String voucher(@PathVariable Long id,@RequestParam(defaultValue="") String code,
      @RequestParam String operation,RedirectAttributes redirect) {
    return action(redirect,ORDERS+"/"+id,"Đã cập nhật voucher.",() -> orders.voucher(id,code,operation));
  }

  @PostMapping("/orders/{id}/finalize")
  public String finalizeOrder(@PathVariable Long id, RedirectAttributes redirect) {
    return action(redirect, ORDERS + "/" + id, "Đã chốt hóa đơn.", () -> orders.finalizeOrder(id));
  }

  @PostMapping("/orders/{id}/pay")
  public String pay(@PathVariable Long id, @RequestParam String method, @RequestParam(required = false) String paymentCode,
      @RequestParam(required = false) String reference,
      @RequestParam(defaultValue = "false") boolean received, Authentication actor, RedirectAttributes redirect) {
    if ("TRANSFER".equals(method) && paymentIntents.usesQr()) {
      return action(redirect, ORDERS + "/" + id, "Đã xác nhận thanh toán chuyển khoản.",
          () -> orders.collectVerifiedTransfer(id, paymentCode, actor.getName()));
    }
    return action(redirect, ORDERS + "/" + id, "Đã xác nhận thanh toán.",
        () -> orders.collect(id, method, reference, received, actor.getName()));
  }

  @PostMapping("/orders/{id}/payment-intent")
  public String createInvoicePayment(@PathVariable Long id, Authentication actor, RedirectAttributes redirect) {
    return action(redirect, ORDERS + "/" + id, "Đã tạo QR cho số tiền còn phải thanh toán.",
        () -> paymentIntents.ensureInvoiceIntent(id, actor.getName()));
  }

  @PostMapping("/orders/{id}/payment-demo")
  public String demoInvoicePayment(@PathVariable Long id, Authentication actor, RedirectAttributes redirect) {
    return action(redirect, ORDERS + "/" + id, "Đã ghi nhận thanh toán hóa đơn.", () -> {
      var checkout = paymentIntents.invoiceCheckout(id);
      if (checkout == null) throw new BookingException(null, "Hãy tạo mã QR trước khi giả lập thanh toán.");
      paymentVerification.simulateInvoiceAsPaid(checkout.intent().getPaymentCode());
    });
  }

  @GetMapping("/orders/{id}/payment-status")
  @ResponseBody
  public PaymentIntentService.PaymentStatus invoicePaymentStatus(@PathVariable Long id) {
    var checkout = paymentIntents.invoiceCheckout(id);
    return paymentIntents.intentStatus(checkout == null ? null : checkout.intent());
  }

  @GetMapping("/shift")
  public String shift(@RequestParam(required = false) Long id, @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "0") int page, Authentication actor, Model model) {
    boolean admin = actor.getAuthorities().stream().anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    String historyStatus = "OPEN".equals(status) || "CLOSED".equals(status) ? status : "";
    var view = shifts.overview(actor.getName(), id, admin, historyStatus, page);
    if (id != null && view.selected() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    model.addAttribute("view", view);
    model.addAttribute("historyStatus", historyStatus);
    var selected = view.selected();
    if (selected != null) {
      var until = selected.getClosedAt() == null ? LocalDateTime.now(schedule.getClock()) : selected.getClosedAt();
      var elapsed = Duration.between(selected.getOpenedAt(), until);
      if (elapsed.isNegative()) elapsed = Duration.ZERO;
      model.addAttribute("shiftElapsed", elapsed);
      model.addAttribute("shiftAcrossDays", selected.getClosedAt() == null
          && selected.getOpenedAt().toLocalDate().isBefore(until.toLocalDate()));
    }
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
