package t3l.restaurant.t3l_restaurant.service;

import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.dto.MenuOrderForm;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.dto.ReservationUpdateForm;
import t3l.restaurant.t3l_restaurant.entity.DiningOrder;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.ReservationEvent;
import t3l.restaurant.t3l_restaurant.entity.ReservationEmail.Kind;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;
import t3l.restaurant.t3l_restaurant.repository.ReservationEventRepository;
import t3l.restaurant.t3l_restaurant.repository.DiningOrderRepository;
import t3l.restaurant.t3l_restaurant.repository.DishRepository;
import t3l.restaurant.t3l_restaurant.repository.RestaurantTableRepository;
import t3l.restaurant.t3l_restaurant.repository.RestaurantSectionRepository;

import static t3l.restaurant.t3l_restaurant.entity.Reservation.*;

@Service
public class ReservationService {
  public static final List<String> BLOCKING_STATUSES = List.of(PENDING, CONFIRMED, CHECKED_IN, SEATED);
  private static final DateTimeFormatter STAFF_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
  private final ReservationRepository reservations;
  private final RestaurantTableRepository tables;
  private final ReservationSchedule schedule;
  private final Validator validator;
  private final ReservationEmailQueue emails;
  private final ReservationEventRepository events;
  private final DiningOrderRepository orders;
  private final PreorderService preorders;
  private final DishRepository dishes;
  private final StaffShiftService shifts;
  private final RestaurantSectionRepository sections;
  private final AdminAudit audit;
  private final DepositPolicy depositPolicy;
  private final PaymentReferences references;
  private final PaymentIntentService paymentIntents;
  private final VoucherService vouchers;

  public ReservationService(ReservationRepository reservations, RestaurantTableRepository tables,
      ReservationSchedule schedule, Validator validator, ReservationEmailQueue emails,
      ReservationEventRepository events, DiningOrderRepository orders, StaffShiftService shifts,
      PreorderService preorders, DishRepository dishes, RestaurantSectionRepository sections, AdminAudit audit,
      DepositPolicy depositPolicy, PaymentReferences references, PaymentIntentService paymentIntents, VoucherService vouchers) {
    this.reservations = reservations;
    this.tables = tables;
    this.schedule = schedule;
    this.validator = validator;
    this.emails = emails;
    this.events = events;
    this.orders = orders;
    this.preorders = preorders;
    this.dishes = dishes;
    this.shifts = shifts;
    this.sections = sections;
    this.audit = audit;
    this.depositPolicy = depositPolicy;
    this.references = references;
    this.paymentIntents = paymentIntents;
    this.vouchers=vouchers;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Reservation create(ReservationForm form) {
    return create(form, null);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Reservation create(ReservationForm form, UserAccount customer) {
    return createBooking(form, customer, null, null, false);
  }

  @PreAuthorize("hasAnyRole('STAFF', 'ADMIN') and #actor == authentication.name")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Reservation createStaffBooking(ReservationForm form, Long tableId, boolean walkIn, String actor, UserAccount customer) {
    form.setGuestName(customer.getFullName());
    form.setGuestPhone(customer.getPhone());
    form.setGuestEmail(customer.getEmail());
    return createBooking(form, customer, actor, tableId, walkIn);
  }

  private Reservation createBooking(ReservationForm form, UserAccount customer, String actor, Long tableId, boolean walkIn) {
    if (walkIn) {
      LocalDateTime now = now();
      form.setDate(now.toLocalDate());
      form.setTime(now.toLocalTime());
    }
    // Validate here as well as in MVC so all callers use the same booking rules.
    validator.validate(form).stream().filter(violation -> {
      String field = violation.getPropertyPath().toString();
      return !(actor != null && "consentAccepted".equals(field));
    }).findFirst().ifPresent(violation -> {
      throw new BookingException(violation.getPropertyPath().toString(), violation.getMessage());
    });
    List<RestaurantTable> active = tables.lockActiveTables();
    LocalDateTime start = LocalDateTime.of(form.getDate(), form.getTime());
    LocalDateTime end = walkIn ? start.plusMinutes(schedule.getDurationMinutes()) : schedule.validateAndGetEnd(form);
    String preferred = form.getPreferredArea() == null ? "" : form.getPreferredArea().trim();

    // Locks are held until the booking commits. Status updates acquire the same locks first.
    Long requestedTableId = tableId == null ? form.getTableId() : tableId;
    RestaurantTable assigned = requestedTableId == null
        ? selectTable(active, start, end, form.getGuests(), preferred, null, walkIn)
        : requireTable(active, requestedTableId, start, end, form.getGuests(), null, walkIn);

    String code = "T3L-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
    Reservation booking = new Reservation(code, form.getGuestName().trim(), form.getGuestPhone() == null ? "" : form.getGuestPhone().trim(),
        form.getGuestEmail() == null ? null : form.getGuestEmail().trim(), assigned,
        form.getDate(), form.getTime(), form.getGuests(),
        form.getNote() == null ? null : form.getNote().trim(), form.isConsentAccepted());
    booking.setBookingDetails(end, preferred.isEmpty() ? null : preferred);
    booking.assignCustomer(customer);
    if (customer == null && !preorders.selection(form).isEmpty()) {
      throw new BookingException("preorderQuantities", "Vui lòng đăng nhập để đặt món trước.");
    }
    var preorder = preorders.quote(form, null);
    if (preorder.voucherCode()!=null && !preorder.voucherCode().isBlank() && (customer==null || actor!=null)) {
      throw new BookingException("voucherCode","Hãy đăng nhập khách hàng để sử dụng voucher khi đặt món trước.");
    }
    if (actor != null) {
      booking.updateStatus(CONFIRMED);
      if (walkIn) {
        booking.checkIn(start);
        booking.seat(shifts.requireOpenShiftForUpdate(actor), start, end);
      }
    }
    Reservation saved = reservations.saveAndFlush(booking);
    var preorderOrder=preorders.apply(saved, null, preorder, now());
    var net=preorder.total().subtract(preorderOrder==null ? BigDecimal.ZERO : preorderOrder.getVoucherDiscount());
    booking.priceDeposit(walkIn ? BigDecimal.ZERO : depositPolicy.required(assigned, net),
        depositPolicy.paymentDeadline(booking, now()));
    if (booking.isOpenBooking() && actor == null && !paymentIntents.usesQr()) booking.waitForStaffBeforeDeposit();
    paymentIntents.ensureDepositIntent(saved);
    if (actor == null) emails.enqueue(saved, Kind.RECEIVED, null, null);
    else {
      record(saved, actor, walkIn ? "Đặt bàn ngay và bắt đầu phục vụ." : "Tạo và xác nhận đặt bàn qua nhân viên.");
      if (!walkIn) emails.enqueue(saved, Kind.CONFIRMED, null, null);
    }
    return saved;
  }

  public boolean canAddMenuItems(Reservation booking, DiningOrder order, boolean employee) {
    if (order != null && !DiningOrder.DRAFT.equals(order.getStatus())) return false;
    if (booking.isOpenBooking()) {
      var now = now();
      return booking.getStartsAt().isAfter(now) && !booking.hasExpiredDeposit(now)
          && !"TRANSFER_SUBMITTED".equals(booking.getDepositStatus());
    }
    return employee && List.of(CHECKED_IN, SEATED).contains(booking.getStatus());
  }

  @PreAuthorize("#actor == authentication.name and ((#employee and hasAnyRole('STAFF', 'ADMIN')) or "
      + "(!#employee and hasRole('BUYER') and #customer != null and #customer.username == authentication.name))")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void addMenuItems(Long reservationId, MenuOrderForm form, UserAccount customer, String actor, boolean employee) {
    Reservation booking = lockBooking(reservationId);
    if (!employee && !owns(booking, customer)) {
      throw new BookingException(null, "Không tìm thấy lượt đặt bàn của bạn.");
    }
    DiningOrder order = orders.findByReservation_Id(reservationId).orElse(null);
    if (!canAddMenuItems(booking, order, employee)) {
      throw new BookingException(null, "Lượt đặt bàn này hiện không thể thêm món.");
    }
    validator.validate(form).stream().findFirst().ifPresent(error -> {
      throw new BookingException(null, error.getMessage());
    });
    if (order == null) order = new DiningOrder(booking, actor, now());
    for (var item : form.getItems()) {
      var dish = dishes.findSellable(item.getDishId()).orElseThrow(() ->
          new BookingException(null, "Một món đã ngừng phục vụ. Vui lòng chọn lại."));
      order.addItem(dish, item.getQuantity(), item.getNote(), booking.isOpenBooking());
    }
    orders.save(order);
    if (booking.isOpenBooking()) repriceDeposit(booking);
    else vouchers.reprice(order, order.getCurrentTotal());
  }

  public BigDecimal minimumDeposit(RestaurantTable table) {
    return depositPolicy.minimum(table);
  }

  public String depositRefundTerms() { return depositPolicy.refundTerms(); }

  // Callers must hold the allocation locks for the lifetime of their transaction.
  RestaurantTable selectTable(List<RestaurantTable> activeTables, LocalDateTime start,
      LocalDateTime end, int guests, String preferred, Long excludedReservationId) {
    return selectTable(activeTables, start, end, guests, preferred, excludedReservationId, false);
  }

  @Transactional(readOnly = true)
  public void checkAvailability(ReservationForm form) {
    LocalDateTime end = schedule.validateAndGetEnd(form);
    var start = form.getDate().atTime(form.getTime());
    var active = tables.findByActiveTrueOrderByCodeAsc();
    if (form.getTableId() == null) selectTable(active, start, end, form.getGuests(), "", null);
    else requireTable(active, form.getTableId(), start, end, form.getGuests(), null, false);
  }

  private RestaurantTable selectTable(List<RestaurantTable> activeTables, LocalDateTime start,
      LocalDateTime end, int guests, String preferred, Long excludedReservationId, boolean immediate) {
    if (!preferred.isEmpty() && activeTables.stream().noneMatch(t -> preferred.equals(t.getTableType()))) {
      throw new BookingException("preferredArea", "Khu vực đã chọn không còn phục vụ. Vui lòng chọn lại.");
    }
    List<RestaurantTable> candidates = activeTables.stream()
        .filter(table -> table.getCapacity() >= guests)
        .sorted(Comparator.comparing((RestaurantTable table) ->
                !preferred.isEmpty() && !preferred.equals(table.getTableType()))
            .thenComparingInt(RestaurantTable::getCapacity).thenComparing(RestaurantTable::getId))
        .toList();
    if (candidates.isEmpty()) {
      throw new BookingException("guests", "Hiện chưa có bàn đủ chỗ cho " + guests
          + " khách. Vui lòng chọn số khách khác hoặc liên hệ nhà hàng.");
    }
    List<Reservation> blocking = findBlocking(start, end, immediate);
    return candidates.stream()
        .filter(table -> blocking.stream().noneMatch(r -> !Objects.equals(r.getId(), excludedReservationId) && r.getTable() != null
            && Objects.equals(r.getTable().getId(), table.getId())))
        .findFirst().orElseThrow(() -> new BookingException(null,
            "Rất tiếc, không còn bàn phù hợp trong khung giờ này. Vui lòng chọn giờ hoặc ngày khác."));

  }

  @Transactional(readOnly = true)
  public java.util.Map<Long, Reservation> blockingByTable(LocalDateTime start, LocalDateTime end) {
    var blocking = new java.util.HashMap<Long, Reservation>();
    findBlocking(start, end, false).stream()
        .filter(booking -> booking.getTable() != null)
        .sorted(Comparator.comparingInt((Reservation booking) -> switch (booking.getStatus()) {
          case SEATED -> 0;
          case CHECKED_IN -> 1;
          case CONFIRMED -> 2;
          default -> 3;
        }).thenComparing(Reservation::getStartsAt).thenComparing(Reservation::getId))
        .forEach(booking -> blocking.putIfAbsent(booking.getTable().getId(), booking));
    return blocking;
  }

  private List<Reservation> findBlocking(LocalDateTime start, LocalDateTime end, boolean immediate) {
    var rules = schedule.rules();
    var now = now();
    // Include the previous date for legacy bookings that run past midnight.
    var matches = new ArrayList<>(reservations.findByReservationDateBetweenAndStatusNot(
            start.toLocalDate().minusDays(1), end.toLocalDate(), CANCELLED).stream()
        .filter(r -> !r.isTerminal() && !SEATED.equals(r.getStatus()) && !r.hasExpiredDeposit(now))
        .filter(r -> r.getStartsAt().isBefore(end)
            && r.effectiveEndsAt(rules.duration()).isAfter(start))
        .toList());
    reservations.findByStatusInAndTableIsNotNull(List.of(SEATED)).stream()
        .filter(r -> {
          if (immediate) return true;
          var seated = r.getSeatedAt() == null ? r.getStartsAt() : r.getSeatedAt();
          var expectedEnd = r.effectiveEndsAt(rules.duration());
          var occupiedUntil = expectedEnd.isAfter(now) ? expectedEnd : now.plusMinutes(rules.slot());
          return seated.isBefore(end) && occupiedUntil.isAfter(start);
        })
        .forEach(matches::add);
    return matches;
  }

  private RestaurantTable requireTable(List<RestaurantTable> active, Long tableId,
      LocalDateTime start, LocalDateTime end, int guests, Long excluded, boolean immediate) {
    var table = active.stream().filter(candidate -> candidate.getId().equals(tableId)).findFirst()
        .orElseThrow(() -> new BookingException("tableId", "Bàn đã chọn không còn phục vụ."));
    if (table.getCapacity() < guests) throw new BookingException("tableId", "Bàn không đủ chỗ cho số khách.");
    if (findBlocking(start, end, immediate).stream().anyMatch(r -> !Objects.equals(r.getId(), excluded)
        && r.getTable() != null && r.getTable().getId().equals(tableId))) {
      throw new BookingException("tableId", "Bàn đang phục vụ hoặc có lịch trùng với thời gian này.");
    }
    return table;
  }

  @Transactional(readOnly = true)
  public List<Reservation> findRecent() {
    return reservations.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
  }

  @Transactional(readOnly = true)
  public Reservation findByCode(String code) {
    return reservations.findByCode(code).orElse(null);
  }

  @Transactional(readOnly = true)
  public List<Reservation> customerBookings(Long customerId, boolean past) {
    LocalDateTime now = LocalDateTime.now(schedule.getClock());
    var bookings = reservations.findByCustomerIdOrderByReservationDateAscReservationTimeAscIdAsc(customerId)
        .stream().filter(booking -> past == isPast(booking, now)).toList();
    return past ? bookings.reversed() : bookings;
  }

  public boolean isPast(Reservation booking) {
    return isPast(booking, LocalDateTime.now(schedule.getClock()));
  }

  private boolean isPast(Reservation booking, LocalDateTime now) {
    return booking.isTerminal() || (booking.isOpenBooking() && booking.getStartsAt().isBefore(now));
  }

  @Transactional(readOnly = true)
  public Reservation findVisibleByCode(String code, UserAccount customer, boolean guestVerified) {
    if (code == null || !code.matches("T3L-[A-Z0-9-]{4,16}") || (customer == null && !guestVerified)) return null;
    Reservation booking = findByCode(code);
    if (booking == null) return null;
    Long owner = booking.getCustomerId();
    return (owner == null ? guestVerified : owns(booking, customer)) ? booking : null;
  }

  public boolean owns(Reservation booking, UserAccount customer) {
    return customer != null && customer.getId().equals(booking.getCustomerId());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void updateStatus(Long id, String status) {
    if (!CONFIRMED.equals(status) && !CANCELLED.equals(status)) {
      throw new IllegalArgumentException("Trạng thái không hợp lệ.");
    }
    List<RestaurantTable> active = tables.lockActiveTables();
    Reservation booking = lockBooking(id);
    decideBooking(booking, status, active, null);
  }

  private void decideBooking(Reservation booking, String status, List<RestaurantTable> active, String actor) {
    if (status.equals(booking.getStatus())) return;
    boolean cancelArrived = actor != null && CANCELLED.equals(status) && CHECKED_IN.equals(booking.getStatus());
    boolean confirmationAfterNoShowWindow = CONFIRMED.equals(status)
        && !booking.getStartsAt().plusMinutes(schedule.getNoShowMinutes()).isAfter(now());
    if ((!booking.isOpenBooking() && !cancelArrived)
        || (actor == null && !booking.getStartsAt().isAfter(now())) || confirmationAfterNoShowWindow) {
      throw new IllegalArgumentException("Đặt bàn đã kết thúc, đã hủy hoặc hết hiệu lực; không thể sửa trạng thái.");
    }
    if (CONFIRMED.equals(status)) requireTable(active, tableId(booking), booking.getStartsAt(),
        booking.effectiveEndsAt(schedule.getDurationMinutes()), booking.getGuestCount(), booking.getId(), false);
    if (paymentIntents.usesQr() && CONFIRMED.equals(status) && booking.isDepositOutstanding()) {
      throw new BookingException(null, "Chưa nhận đủ tiền cọc đã yêu cầu. Hãy chờ SePay xác minh giao dịch trước khi xác nhận.");
    }
    Kind kind = CONFIRMED.equals(status) ? Kind.CONFIRMED : PENDING.equals(booking.getStatus()) ? Kind.REJECTED : Kind.CANCELLED;
    booking.updateStatus(status);
    if (CONFIRMED.equals(status)) {
      booking.approveDepositRequirement(depositPolicy.paymentDeadline(booking, now()));
      removeUnavailablePreorders(booking, actor, true);
    } else {
      booking.closeDeposit(100, false, now());
      preorders.close(booking.getId());
    }
    record(booking, actor, booking.getStatusLabel() + ".");
    emails.enqueue(booking, kind, null, null);
  }

  @PreAuthorize("hasAnyRole('STAFF', 'ADMIN') and #actor == authentication.name")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void staffStatus(Long id, String status, String actor) {
    var active = tables.lockActiveTables();
    var booking = lockBooking(id);
    if (booking.getStatus().equals(status) || ("RECEIVE".equals(status) && SEATED.equals(booking.getStatus()))) return;
    LocalDateTime now = now();
    if (expireDeposit(booking, now)) return;
    switch (status) {
      case CONFIRMED, CANCELLED -> decideBooking(booking, status, active, actor);
      case CHECKED_IN -> checkIn(booking, now);
      case SEATED, "RECEIVE" -> {
        if (("RECEIVE".equals(status) || SEATED.equals(status)) && CONFIRMED.equals(booking.getStatus())) {
          checkIn(booking, now);
        }
        if (!CHECKED_IN.equals(booking.getStatus())) throw new BookingException(null, "Hãy check-in khách trước khi nhận bàn.");
        if (!booking.getReservationDate().equals(now.toLocalDate())) {
          throw new BookingException("date", "Chỉ nhận bàn chờ trong ngày đặt.");
        }
        if (booking.isDepositOutstanding()) throw new BookingException(null, "Chưa nhận đủ tiền cọc.");
        LocalDateTime end = expectedServiceEnd(booking, now);
        requireTable(active, tableId(booking), now, end, booking.getGuestCount(), id, true);
        booking.seat(shifts.requireOpenShiftForUpdate(actor), now, end);
        removeUnavailablePreorders(booking, actor, false);
      }
      case COMPLETED -> {
        if (!SEATED.equals(booking.getStatus())) throw new BookingException(null, "Chỉ kết thúc lượt đang phục vụ.");
        orders.findByReservation_Id(id).ifPresent(order -> {
          if (DiningOrder.DRAFT.equals(order.getStatus()) && !order.hasItems()) {
            vouchers.release(order);
            order.voidEmptyDraft();
          }
          else if (!DiningOrder.PAID.equals(order.getStatus())) {
            throw new BookingException(null, "Hãy thanh toán hóa đơn trước khi kết thúc phục vụ.");
          }
        });
        if (booking.getAvailableDeposit().signum() > 0) booking.closeDeposit(100, false, now);
        finishService(booking, actor, now);
      }
      case NO_SHOW -> {
        if (!CONFIRMED.equals(booking.getStatus()) || now.isBefore(booking.getStartsAt().plusMinutes(schedule.getNoShowMinutes()))) {
          throw new BookingException(null, "Chỉ đánh dấu khách không đến sau giờ đặt ít nhất " + schedule.getNoShowMinutes() + " phút.");
        }
        booking.updateStatus(NO_SHOW);
        booking.closeDeposit(depositPolicy.noShowPercent(), false, now);
        preorders.close(id);
        emails.enqueue(booking, Kind.NO_SHOW, null, null);
      }
      default -> throw new BookingException(null, "Trạng thái không hợp lệ.");
    }
    if (!List.of(CONFIRMED, CANCELLED, COMPLETED).contains(status)) record(booking, actor, booking.getStatusLabel() + ".");
  }

  private void checkIn(Reservation booking, LocalDateTime now) {
    if (booking.isDepositOutstanding()) {
      throw new BookingException(null, "Chưa xác nhận thanh toán cọc cho lượt đặt bàn này.");
    }
    if (!CONFIRMED.equals(booking.getStatus()) || !booking.getReservationDate().equals(now.toLocalDate())) {
      throw new BookingException(null, "Chỉ nhận lượt đã xác nhận trong ngày đặt.");
    }
    booking.checkIn(now);
  }

  void finishService(Reservation booking, String actor, LocalDateTime now) {
    booking.complete(now);
    record(booking, actor, booking.getStatusLabel() + ".");
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void submitDepositReference(Long id, Long customerId, String reference) {
    if (paymentIntents.usesQr()) throw new BookingException(null, "Hãy dùng mã thanh toán do hệ thống cấp.");
    String normalized = references.normalize(reference, 4);
    tables.lockInventory();
    Reservation booking = lockBooking(id);
    if (!Objects.equals(booking.getCustomerId(), customerId)) throw new BookingException(null, "Bạn không có quyền gửi mã cho lượt đặt này.");
    if (!"WAITING_PAYMENT".equals(booking.getDepositStatus()) || !CONFIRMED.equals(booking.getStatus())
        || booking.hasExpiredDeposit(now())) {
      throw new BookingException(null, "Đặt bàn chưa được duyệt hoặc không còn chờ thanh toán cọc.");
    }
    references.requireUnused(normalized);
    booking.submitDepositReference(normalized, depositPolicy.reviewDeadline(booking, now()));
  }

  @PreAuthorize("hasAnyRole('STAFF', 'ADMIN') and #actor == authentication.name")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void verifyDeposit(Long id, boolean received, BigDecimal amount, String expectedReference, String actor) {
    tables.lockInventory();
    Reservation booking = lockBooking(id);
    expireDeposit(booking, now());
    if (!List.of("TRANSFER_SUBMITTED", "CLOSED_TRANSFER_PENDING").contains(booking.getDepositStatus())
        || !Objects.equals(booking.getDepositReference(), expectedReference)) {
      throw new BookingException(null, "Chưa có giao dịch cọc đang chờ xác nhận.");
    }
    var expected = booking.getDepositSubmittedAmount();
    if (received && (amount == null || expected.compareTo(amount) != 0)) {
      throw new BookingException(null, "Số tiền đã nhận phải khớp với số tiền yêu cầu đối soát.");
    }
    applyDepositVerification(booking, received, expected, expectedReference, actor);
  }

  @PreAuthorize("hasAnyRole('STAFF', 'ADMIN') and #actor == authentication.name")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void recordStaffDeposit(Long id, BigDecimal amount, String reference, String actor) {
    if (paymentIntents.usesQr()) throw new BookingException(null, "Hãy dùng mã QR cọc của lượt đặt này.");
    String normalized = references.normalize(reference, 4);
    tables.lockInventory();
    var booking = lockBooking(id);
    var now = now();
    if (!CONFIRMED.equals(booking.getStatus()) || !"WAITING_PAYMENT".equals(booking.getDepositStatus())
        || booking.hasExpiredDeposit(now)) {
      throw new BookingException(null, "Lượt đặt này hiện không chờ thanh toán cọc.");
    }
    if (amount == null || amount.signum() <= 0 || amount.compareTo(booking.getDepositRemaining()) != 0) {
      throw new BookingException(null, "Số tiền đã nhận phải bằng số tiền cọc còn thiếu.");
    }
    booking.submitDepositReference(normalized, depositPolicy.reviewDeadline(booking, now));
    applyDepositVerification(booking, true, amount, normalized, actor);
  }

  private void applyDepositVerification(Reservation booking, boolean received, BigDecimal expected,
      String reference, String actor) {
    if (received) references.claim(reference, "DEPOSIT", booking.getId());
    booking.verifyDeposit(received);
    if (!received) expireDeposit(booking, now());
    var event = record(booking, actor, received ? "Đã nhận cọc " + expected.toPlainString() + " đ · " + reference
        : "Từ chối giao dịch cọc · " + reference);
    emails.enqueue(booking, received ? Kind.DEPOSIT_VERIFIED : Kind.DEPOSIT_REJECTED, event.getId(), null);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean receiveVerifiedDeposit(Long id, BigDecimal amount, String reference,
      LocalDateTime transactionAt, boolean demo) {
    if (id == null) return false;
    Reservation booking = reservations.lockById(id).orElse(null);
    if (booking == null || booking.hasExpiredDeposit(now())
        || !booking.receiveVerifiedDeposit(amount, reference, demo)) return false;
    String actor = demo ? "DEMO" : "SEPAY";
    var event = record(booking, actor, "SePay đã xác minh tiền cọc " + amount.toPlainString() + " đ"
        + (demo ? " · DEMO" : "") + (reference == null ? "" : " · " + reference));
    emails.enqueue(booking, Kind.DEPOSIT_VERIFIED, event.getId(), demo ? "Khoản thanh toán này ở chế độ DEMO." : null);
    return true;
  }

  @PreAuthorize("hasAnyRole('STAFF', 'ADMIN') and #actor == authentication.name")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void refundDeposit(Long id, BigDecimal amount, String reference, String actor) {
    var booking = lockBooking(id);
    var due = booking.getRefundRemaining();
    if (!"REFUND_PENDING".equals(booking.getDepositStatus()) || amount == null || due.compareTo(amount) != 0) {
      throw new BookingException(null, "Số tiền hoàn phải khớp với số tiền đang chờ hoàn.");
    }
    var normalized = references.normalize(reference, 4);
    references.claim(normalized, "REFUND", id);
    booking.markDepositRefunded();
    var event = record(booking, actor, "Đã hoàn cọc " + due.toPlainString() + " đ · " + normalized);
    emails.enqueue(booking, Kind.DEPOSIT_REFUNDED, event.getId(), null);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void releaseExpiredDeposit(Long id) {
    tables.lockInventory();
    reservations.lockById(id).ifPresent(booking -> expireDeposit(booking, now()));
  }

  private boolean expireDeposit(Reservation booking, LocalDateTime now) {
    if (!booking.hasExpiredDeposit(now)) return false;
    booking.updateStatus(CANCELLED);
    booking.closeDeposit(100, true, now);
    preorders.close(booking.getId());
    record(booking, null, "Giải phóng bàn do quá hạn cọc.");
    emails.enqueue(booking, Kind.DEPOSIT_EXPIRED, null, null);
    return true;
  }

  void requireDepositChangeAllowed(Reservation booking) {
    if (booking.hasExpiredDeposit(now())) throw new BookingException(null, "Lượt đặt đã quá hạn cọc.");
    if ("TRANSFER_SUBMITTED".equals(booking.getDepositStatus())) {
      throw new BookingException(null, "Hãy đối soát giao dịch cọc trước khi đổi lịch hoặc chuyển bàn.");
    }
  }

  void repriceDeposit(Reservation booking) {
    var order=preorders.order(booking.getId());
    var gross=order==null ? BigDecimal.ZERO : order.getPreorderTotal();
    boolean affected=vouchers.affected(order);
    boolean released=order!=null && vouchers.reprice(order,gross);
    var net=gross.subtract(order==null ? BigDecimal.ZERO : order.getVoucherDiscount());
    var required=depositPolicy.required(booking.getTable(),net);
    if (affected) vouchers.guardDeposit(booking,required);
    booking.priceDeposit(required,depositPolicy.paymentDeadline(booking,now()));
    if (released) record(booking,null,"Voucher đã được gỡ vì giỏ món không còn đủ điều kiện.");
    if (PENDING.equals(booking.getStatus()) && !paymentIntents.usesQr()) booking.waitForStaffBeforeDeposit();
    paymentIntents.ensureDepositIntent(booking);
  }

  @PreAuthorize("hasAnyRole('STAFF', 'ADMIN') and #actor == authentication.name")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void removeUnavailablePreorders(Long id, String actor) {
    tables.lockInventory();
    var booking = lockBooking(id);
    if (!booking.isOpenBooking()) throw new BookingException(null, "Chỉ cập nhật món trước khi khách check-in.");
    requireDepositChangeAllowed(booking);
    if (!removeUnavailablePreorders(booking, actor, true)) throw new BookingException(null, "Không có món đặt trước đã hết hoặc ngừng bán.");
  }

  private boolean removeUnavailablePreorders(Reservation booking, String actor, boolean reprice) {
    var order=preorders.order(booking.getId());
    var removed = preorders.removeUnavailable(order);
    if (removed.isEmpty()) return false;
    boolean hadVoucher=order.getVoucherCode()!=null;
    if (reprice) repriceDeposit(booking);
    else if (vouchers.reprice(order,order.getCurrentTotal()))
      record(booking,actor,"Voucher đã được gỡ vì giỏ món không còn đủ điều kiện.");
    String note = "Đã bỏ " + removed.size() + " món đặt trước không còn phục vụ.";
    if (hadVoucher && order.getVoucherCode()==null) note+=" Voucher đã được gỡ vì giỏ món không còn đủ điều kiện.";
    var event = record(booking, actor, note);
    emails.enqueue(booking, Kind.PREORDER_UNAVAILABLE, event.getId(), note);
    return true;
  }

  @PreAuthorize("hasAnyRole('STAFF', 'ADMIN') and #actor == authentication.name")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void moveTable(Long id, Long tableId, String actor) {
    var active = tables.lockActiveTables();
    var booking = lockBooking(id);
    LocalDateTime now = now();
    boolean immediate = SEATED.equals(booking.getStatus()) || CHECKED_IN.equals(booking.getStatus());
    if (!immediate && (!booking.isOpenBooking() || !booking.getStartsAt().isAfter(now))) {
      throw new BookingException(null, "Lượt đặt bàn này không còn hiệu lực chuyển bàn.");
    }
    if (Objects.equals(tableId(booking), tableId)) return;
    if (!immediate) requireDepositChangeAllowed(booking);
    LocalDateTime end = moveEnd(booking, now, immediate);
    RestaurantTable destination = requireTable(active, tableId, immediate ? now : booking.getStartsAt(),
        end, booking.getGuestCount(), id, true);
    if (booking.getTable() != null && destination.getKind() != booking.getTable().getKind()) {
      throw new BookingException(null, "Hãy chọn bàn trống cùng loại với bàn hiện tại.");
    }
    String from = booking.getTable() == null ? "chưa phân bàn" : booking.getTable().getCode();
    booking.moveTo(destination, end);
    if (!immediate) repriceDeposit(booking);
    record(booking, actor, "Chuyển bàn: " + from + " → " + destination.getCode() + ".");
  }

  @Transactional(readOnly = true)
  public List<RestaurantTable> moveOptions(Reservation booking) {
    LocalDateTime now = now();
    boolean immediate = List.of(SEATED, CHECKED_IN).contains(booking.getStatus());
    if (immediate && !inServiceHours(now) && !SEATED.equals(booking.getStatus())) return List.of();
    var blocked = findBlocking(immediate ? now : booking.getStartsAt(), moveEnd(booking, now, immediate), true);
    return tables.findByActiveTrueOrderByCodeAsc().stream()
        .filter(table -> !Objects.equals(tableId(booking), table.getId()) && table.getCapacity() >= booking.getGuestCount())
        .filter(table -> booking.getTable() == null || booking.getTable().getKind() == table.getKind())
        .filter(table -> blocked.stream().noneMatch(other -> Objects.equals(tableId(other), table.getId())))
        .toList();
  }

  private LocalDateTime moveEnd(Reservation booking, LocalDateTime now, boolean immediate) {
    if (!immediate) return booking.effectiveEndsAt(schedule.getDurationMinutes());
    if (!inServiceHours(now) && SEATED.equals(booking.getStatus())) return now.plusMinutes(schedule.getSlotMinutes());
    return expectedServiceEnd(booking, now);
  }

  @PreAuthorize("hasAnyRole('STAFF', 'ADMIN') and #actor == authentication.name")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void rescheduleStaffBooking(Long id, ReservationUpdateForm form, Long tableId, String actor) {
    validator.validate(form).stream().findFirst().ifPresent(violation -> {
      throw new BookingException(violation.getPropertyPath().toString(), violation.getMessage());
    });
    var active = tables.lockActiveTables();
    var booking = lockBooking(id);
    if (!booking.isOpenBooking()) throw new BookingException(null, "Chỉ đổi lịch đặt bàn chưa check-in.");
    requireDepositChangeAllowed(booking);
    var end = schedule.validateAndGetEnd(form.scheduleForm());
    var start = LocalDateTime.of(form.getDate(), form.getTime());
    String area = form.getPreferredArea() == null ? "" : form.getPreferredArea().trim();
    RestaurantTable destination = tableId == null ? selectTable(active, start, end, form.getGuests(), area, id)
        : requireTable(active, tableId, start, end, form.getGuests(), id, false);
    if (booking.getStartsAt().equals(start) && booking.getGuestCount() == form.getGuests()
        && Objects.equals(tableId(booking), destination.getId())
        && Objects.equals(booking.getPreferredArea() == null ? "" : booking.getPreferredArea(), area)) return;
    String previous = booking.getStartsAt().format(STAFF_TIME) + " · " + booking.getGuestCount() + " khách";
    String previousStatus = booking.getStatus();
    booking.reschedule(destination, form.getDate(), form.getTime(), end, form.getGuests(), area);
    booking.updateStatus(previousStatus);
    repriceDeposit(booking);
    var event = record(booking, actor, "Đổi lịch: " + previous + " → " + start.format(STAFF_TIME)
        + " · " + booking.getGuestCount() + " khách · " + destination.getCode() + ".");
    emails.enqueueStaffReschedule(booking, event.getId(), form.getReason());
  }

  @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void setTableActive(Long id, boolean active) {
    var table = tables.lockInventory().stream().filter(item -> item.getId().equals(id)).findFirst()
        .orElseThrow(() -> new BookingException(null, "Không tìm thấy bàn."));
    if (table.isActive() == active) return;
    if (active && sections.findByKindAndNameIgnoreCase("AREA", table.getTableType())
        .filter(section -> !section.isActive()).isPresent()) {
      throw new BookingException(null, "Khu vực của bàn đang tạm ngưng sử dụng.");
    }
    if (!active && tableInUse(id)) {
      throw new BookingException(null, "Bàn đang giữ chỗ hoặc có khách. Hãy chuyển hoặc kết thúc các lượt đặt bàn trước.");
    }
    table.setActive(active);
    audit.record(active ? "Mở lại bàn" : "Tạm ngưng bàn", table.getCode());
  }

  // The caller holds the inventory locks before checking, so bookings cannot race an inventory change.
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public boolean tableInUse(Long id) {
    LocalDateTime now = now();
    int duration = schedule.getDurationMinutes();
    return reservations.findByStatusInAndTableIsNotNull(List.of(SEATED, CHECKED_IN)).stream()
        .anyMatch(booking -> Objects.equals(tableId(booking), id))
        || reservations.findByTable_IdAndStatusInAndReservationDateGreaterThanEqual(
            id, List.of(PENDING, CONFIRMED), now.toLocalDate().minusDays(1)).stream()
            .anyMatch(booking -> booking.effectiveEndsAt(duration).isAfter(now) && !booking.hasExpiredDeposit(now));
  }

  private LocalDateTime expectedServiceEnd(Reservation booking, LocalDateTime now) {
    var planned = booking.effectiveEndsAt(schedule.getDurationMinutes());
    var normalEnd = now.plusMinutes(schedule.getDurationMinutes());
    if (!inServiceHours(now)) return normalEnd;
    var closing = now.toLocalDate().atTime(schedule.getClosingTime());
    if (planned.isAfter(now) && !planned.isAfter(normalEnd) && !planned.isAfter(closing)) return planned;
    return normalEnd.isBefore(closing) ? normalEnd : closing;
  }

  private boolean inServiceHours(LocalDateTime now) {
    return !now.toLocalTime().isBefore(schedule.getOpeningTime()) && now.toLocalTime().isBefore(schedule.getClosingTime());
  }

  private LocalDateTime now() {
    return LocalDateTime.now(schedule.getClock()).truncatedTo(ChronoUnit.SECONDS);
  }

  private Reservation lockBooking(Long id) {
    return reservations.lockById(id).orElseThrow(() -> new BookingException(null, "Không tìm thấy yêu cầu đặt bàn."));
  }

  private Long tableId(Reservation booking) {
    return booking.getTable() == null ? null : booking.getTable().getId();
  }

  private ReservationEvent record(Reservation booking, String actor, String description) {
    audit.record("Cập nhật đặt bàn", booking.getCode() + " · " + description);
    return events.save(new ReservationEvent(booking, actor == null ? "Hệ thống" : actor, description, now()));
  }
}
