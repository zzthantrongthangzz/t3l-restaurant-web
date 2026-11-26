package t3l.restaurant.t3l_restaurant.service;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.entity.DiningOrder;
import t3l.restaurant.t3l_restaurant.entity.PaymentIntent;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.ReservationChangeRequest;
import t3l.restaurant.t3l_restaurant.entity.ReservationEvent;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;
import t3l.restaurant.t3l_restaurant.repository.ReservationChangeRequestRepository;
import t3l.restaurant.t3l_restaurant.repository.DiningOrderRepository;
import t3l.restaurant.t3l_restaurant.repository.PaymentIntentRepository;
import t3l.restaurant.t3l_restaurant.repository.ReservationEventRepository;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;
import t3l.restaurant.t3l_restaurant.repository.RestaurantTableRepository;

import static t3l.restaurant.t3l_restaurant.entity.Reservation.*;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
public class StaffWorkspaceService {
  public static final List<String> STATUSES = List.of(PENDING, CONFIRMED, CHECKED_IN, SEATED, COMPLETED, CANCELLED, NO_SHOW);
  private static final List<String> SCHEDULED = List.of(PENDING, CONFIRMED);
  private static final int RESERVATIONS_PAGE_SIZE = 20;
  private final ReservationRepository reservations;
  private final RestaurantTableRepository tables;
  private final ReservationEventRepository events;
  private final ReservationChangeRequestRepository requests;
  private final DiningOrderRepository orders;
  private final ReservationSchedule schedule;
  private final ReservationService bookings;
  private final PaymentIntentService paymentIntents;
  private final PaymentIntentRepository intents;

  public enum TableState {
    FREE("Bàn trống"), PENDING("Đợi xác nhận"), RESERVED("Đã xác nhận"), ARRIVING("Chờ nhận khách"),
    OCCUPIED("Đang phục vụ"), INACTIVE("Tạm ngưng"), TOO_SMALL("Không đủ chỗ");

    private final String label;
    TableState(String label) { this.label = label; }
    public String getLabel() { return label; }
  }

  public record TableView(RestaurantTable table, TableState state, Reservation current,
                          List<Reservation> otherBookings, BookingActions actions, boolean canOrder, boolean canWalkIn,
                          Reservation nextBooking, LocalDateTime servingUntil, boolean serviceAtRisk,
                          BookingActions nextActions, boolean operational) {
    public String getBoardLabel() {
      return switch (state) {
        case RESERVED -> "Đã đặt";
        case FREE -> "Trống";
        default -> state.getLabel();
      };
    }

    public String getSummaryCategory() {
      return switch (state) {
        case PENDING, RESERVED, ARRIVING -> "WAITING";
        default -> state.name();
      };
    }
  }
  public record Dashboard(LocalDate date, long bookingsToday, long pending, long guestsArrived, long serving,
                          long free, long changeRequests, long awaitingPayment, List<Reservation> upcoming, List<Reservation> toConfirm,
                          List<Reservation> checkedIn) {}
  public record BookingActions(boolean confirm, boolean confirmEnabled, String confirmReason,
                               boolean cancel, boolean receive, boolean receiveEnabled, String receiveReason,
                               boolean complete, boolean noShow, boolean late, boolean moveTable, boolean reschedule) {}

  public record ListAction(boolean visible, boolean enabled, String reason) {}
  public record ReservationRow(String status, String statusLabel, String depositStatus, String depositLabel,
                               BigDecimal depositAmount, BigDecimal depositPaid, BigDecimal depositRemaining,
                               boolean late, ListAction confirm, ListAction receive) {}

  @Transactional(readOnly = true)
  public Dashboard dashboard() {
    LocalDateTime now = LocalDateTime.now(schedule.getClock());
    LocalDate today = now.toLocalDate();
    var bookings = reservations.findByReservationDateOrderByReservationTimeAscIdAsc(today);
    var board = boardAt(today, now, null, 1, false, false);
    var upcoming = Stream.concat(bookings.stream()
        .filter(booking -> SCHEDULED.contains(booking.getStatus()) && !booking.getStartsAt().isBefore(now)),
        reservations.findTop10ByStatusInAndReservationDateGreaterThanOrderByReservationDateAscReservationTimeAscIdAsc(SCHEDULED, today).stream())
        .limit(8).toList();
    return new Dashboard(today, bookings.size(), reservations.countByStatus(PENDING),
        reservations.guestsArrivedOn(today, today.atStartOfDay(), today.plusDays(1).atStartOfDay()),
        board.stream().filter(table -> table.state() == TableState.OCCUPIED).count(),
        board.stream().filter(table -> table.state() == TableState.FREE).count(),
        requests.countByStatus("PENDING"), orders.countByStatus(DiningOrder.FINALIZED), upcoming,
        reservations.findTop8ByStatusOrderByReservationDateAscReservationTimeAscIdAsc(PENDING),
        reservations.findByStatusInAndTableIsNotNull(List.of(CONFIRMED, CHECKED_IN)).stream()
            .filter(booking -> booking.getReservationDate().equals(today))
            .filter(booking -> !booking.getStartsAt().isAfter(now.plusMinutes(schedule.getCheckInMinutes())))
            .sorted(Comparator.comparing(Reservation::getStartsAt)).limit(8).toList());
  }

  @Transactional(readOnly = true)
  public long pendingBookingCount() {
    return reservations.countByStatus(PENDING);
  }

  @Transactional(readOnly = true)
  public List<TableView> board(LocalDate date, LocalTime time, int guests, boolean shiftOpen) {
    return boardAt(date, LocalDateTime.now(schedule.getClock()), time, guests, shiftOpen, true);
  }

  private List<TableView> boardAt(LocalDate date, LocalDateTime now, LocalTime time, int guests, boolean shiftOpen,
      boolean staffTimeline) {
    var rules = schedule.rules();
    boolean today = date.equals(now.toLocalDate());
    boolean live = today && time == null;
    var slotStart = time == null ? null : date.atTime(time);
    var slotEnd = slotStart == null ? null : slotStart.plusMinutes(rules.duration());
    boolean operational = !staffTimeline || (today && (live || (!slotStart.isAfter(now) && slotEnd.isAfter(now))));
    var blocked = slotStart == null ? Map.<Long, Reservation>of() : bookings.blockingByTable(slotStart, slotEnd);
    var scheduledByTable = reservations.findByReservationDateBetweenAndStatusNot(date, date, CANCELLED)
        .stream().filter(booking -> booking.getTable() != null && !booking.hasExpiredDeposit(now) && ReservationService.BLOCKING_STATUSES.contains(booking.getStatus()))
        .sorted(Comparator.comparing(Reservation::getStartsAt))
        .collect(Collectors.groupingBy(booking -> booking.getTable().getId()));
    var arrivedByTable = today
        ? reservations.findByStatusInAndTableIsNotNull(List.of(SEATED, CHECKED_IN)).stream()
            .filter(booking -> !staffTimeline || SEATED.equals(booking.getStatus()) || booking.getReservationDate().equals(date))
            .collect(Collectors.groupingBy(booking -> booking.getTable().getId()))
        : Map.<Long, List<Reservation>>of();
    var walkInEnd = now.plusMinutes(rules.duration());
    var walkInBlocks = today && operational
        ? bookings.blockingByTable(now, walkInEnd)
        : Map.<Long, Reservation>of();
    var bookingIds = Stream.concat(scheduledByTable.values().stream(), arrivedByTable.values().stream())
        .flatMap(List::stream).map(Reservation::getId).distinct().toList();
    var boardOrders = bookingIds.isEmpty() ? Map.<Long, DiningOrder>of()
        : orders.findByReservation_IdIn(bookingIds).stream()
            .collect(Collectors.toMap(order -> order.getReservation().getId(), order -> order));
    List<TableView> board = new ArrayList<>();
    var activeIds = new java.util.HashSet<>(tables.activeIds());
    var inventory = tables.findAllByOrderByTableTypeAscCodeAsc().stream()
        .sorted(Comparator.comparing(RestaurantTable::getKind).thenComparing(RestaurantTable::getCode)).toList();
    for (RestaurantTable table : inventory) {
      var arrived = arrivedByTable.getOrDefault(table.getId(), List.of());
      var scheduled = scheduledByTable.getOrDefault(table.getId(), List.of());
      Reservation current = slotStart != null ? blocked.get(table.getId()) : arrived.stream()
          .min(Comparator.comparing(booking -> !SEATED.equals(booking.getStatus())))
          .orElseGet(() -> scheduled.stream()
              .filter(booking -> staffTimeline && live
                  ? activeNow(booking, now, rules)
                  : !booking.getStartsAt().isBefore(now) || booking.effectiveEndsAt(rules.duration()).isAfter(now))
              .findFirst().orElse(null));
      TableState state = !activeIds.contains(table.getId()) ? TableState.INACTIVE : current == null ? (table.getCapacity() >= guests ? TableState.FREE : TableState.TOO_SMALL)
          : switch (current.getStatus()) {
            case SEATED -> operational ? TableState.OCCUPIED : TableState.RESERVED;
            case PENDING -> TableState.PENDING;
            case CONFIRMED, CHECKED_IN -> staffTimeline && operational && readyForArrival(current, now, rules)
                ? TableState.ARRIVING : TableState.RESERVED;
            default -> TableState.RESERVED;
          };
      boolean canWalkIn = today && operational && activeIds.contains(table.getId())
          && table.getCapacity() >= guests && arrived.isEmpty() && !walkInBlocks.containsKey(table.getId());
      var nextFrom = slotStart == null ? (live ? now : date.atStartOfDay()) : slotStart;
      var next = scheduled.stream().filter(booking -> SCHEDULED.contains(booking.getStatus()))
          .filter(booking -> staffTimeline
              ? !sameBooking(booking, current) && (!booking.getStartsAt().isBefore(nextFrom)
                  || (state == TableState.OCCUPIED && booking.effectiveEndsAt(rules.duration()).isAfter(nextFrom)))
              : booking.effectiveEndsAt(rules.duration()).isAfter(now))
          .findFirst().orElse(null);
      var others = scheduled.stream().filter(booking -> !sameBooking(booking, current)
          && (!staffTimeline || !sameBooking(booking, next))).toList();
      LocalDateTime servingUntil = state == TableState.OCCUPIED ? current.effectiveEndsAt(rules.duration()) : null;
      boolean serviceAtRisk = servingUntil != null && next != null
          && (servingUntil.isAfter(next.getStartsAt()) || !now.plusMinutes(rules.slot()).isBefore(next.getStartsAt()));
      board.add(new TableView(table, state, current, others, current == null ? null : actions(current, now, shiftOpen, rules),
          current != null && (operational || !SEATED.equals(current.getStatus()))
              && bookings.canAddMenuItems(current, boardOrders.get(current.getId()), true), canWalkIn,
          next, servingUntil, serviceAtRisk, staffTimeline && next != null ? actions(next, now, shiftOpen, rules) : null, operational));
    }
    return board;
  }

  private boolean activeNow(Reservation booking, LocalDateTime now, ReservationSchedule.Rules rules) {
    return (!booking.getStartsAt().isAfter(now) && booking.effectiveEndsAt(rules.duration()).isAfter(now))
        || (CONFIRMED.equals(booking.getStatus()) && readyForArrival(booking, now, rules));
  }

  private boolean readyForArrival(Reservation booking, LocalDateTime now, ReservationSchedule.Rules rules) {
    return CHECKED_IN.equals(booking.getStatus()) || (!now.isBefore(booking.getStartsAt().minusMinutes(rules.checkIn()))
        && now.isBefore(booking.getStartsAt().plusMinutes(rules.noShow())));
  }

  private boolean sameBooking(Reservation booking, Reservation other) {
    return other != null && Objects.equals(booking.getId(), other.getId());
  }
  @Transactional(readOnly = true)
  public Page<Reservation> search(LocalDate date, String status, String name, String phone, String code, int page) {
    var nameLike = like(name.toLowerCase(Locale.ROOT));
    var phoneLike = like(ReservationManagementService.canonicalPhone(phone));
    var codeLike = like(code.toUpperCase(Locale.ROOT));
    Specification<Reservation> spec = (root, query, cb) -> {
      List<Predicate> conditions = new ArrayList<>();
      if (date != null) conditions.add(cb.equal(root.get("reservationDate"), date));
      if (STATUSES.contains(status)) conditions.add(cb.equal(root.get("status"), status));
      if (!name.isEmpty()) conditions.add(cb.like(cb.lower(root.get("guestName")), nameLike, '!'));
      if (!phone.isEmpty()) {
        var digits = cb.function("regexp_replace", String.class, root.get("guestPhone"), cb.literal("[^0-9]"), cb.literal(""));
        var canonical = cb.<String>selectCase().when(cb.and(cb.like(digits, "84%"), cb.equal(cb.length(digits), 11)),
            cb.concat("0", cb.substring(digits, 3))).otherwise(digits);
        // An invalid filter should return no matches instead of displaying all contact numbers.
        conditions.add(phoneLike.equals("%%") ? cb.disjunction() : cb.like(canonical, phoneLike, '!'));
      }
      if (!code.isEmpty()) conditions.add(cb.like(cb.upper(root.get("code")), codeLike, '!'));
      return cb.and(conditions.toArray(Predicate[]::new));
    };
    var sort = date == null ? Sort.by(Sort.Direction.DESC, "reservationDate", "reservationTime", "id")
        : Sort.by("reservationTime", "id");
    return reservations.findAll(spec, PageRequest.of(Math.max(page, 0), 20, sort));
  }

  @Transactional(readOnly = true)
  public Page<Reservation> searchQuick(LocalDate date, String status, String query, int page,
      String scope, boolean staffList, LocalDateTime now) {
    String value = query == null ? "" : query.strip();
    String like = like(value.toLowerCase(Locale.ROOT));
    String codeLike = like(value.toUpperCase(Locale.ROOT));
    String phoneValue = ReservationManagementService.canonicalPhone(value);
    String phoneLike = like(phoneValue);
    var noShowCutoff = staffList && "attention".equals(scope) ? now.minusMinutes(schedule.getNoShowMinutes()) : null;
    Specification<Reservation> spec = (root, criteriaQuery, cb) -> {
      List<Predicate> conditions = new ArrayList<>();
      if (date != null) conditions.add(cb.equal(root.get("reservationDate"), date));
      if (STATUSES.contains(status)) conditions.add(cb.equal(root.get("status"), status));
      if (staffList) {
        var awaiting = root.get("status").in(SCHEDULED);
        var expired = cb.and(awaiting, cb.or(
            depositDeadlinePassed(root, cb, "WAITING_PAYMENT", "depositDueAt", now),
            depositDeadlinePassed(root, cb, "TRANSFER_SUBMITTED", "depositReviewDueAt", now)));
        if ("upcoming".equals(scope)) {
          conditions.add(cb.and(awaiting, startsAfter(root, cb, now), cb.not(expired)));
        } else if ("attention".equals(scope)) {
          var pendingRequest = criteriaQuery.subquery(Long.class);
          var change = pendingRequest.from(ReservationChangeRequest.class);
          pendingRequest.select(change.get("id")).where(cb.equal(change.get("reservation").get("id"), root.get("id")),
              cb.equal(change.get("status"), "PENDING"));
          var latestDeposit = criteriaQuery.subquery(Long.class);
          var latest = latestDeposit.from(PaymentIntent.class);
          latestDeposit.select(cb.max(latest.<Long>get("id"))).where(
              cb.equal(latest.get("reservationId"), root.get("id")), cb.equal(latest.get("paymentType"), PaymentIntent.DEPOSIT));
          var review = criteriaQuery.subquery(Long.class);
          var intent = review.from(PaymentIntent.class);
          review.select(intent.get("id")).where(cb.equal(intent.get("id"), latestDeposit),
              cb.equal(intent.get("status"), PaymentIntent.REVIEW_REQUIRED));
          conditions.add(cb.or(cb.exists(pendingRequest), cb.exists(review), expired,
              cb.and(awaiting, cb.not(startsAfter(root, cb, noShowCutoff))),
              root.get("depositStatus").in("TRANSFER_SUBMITTED", "PAYMENT_REVIEW_REQUIRED", "REFUND_PENDING", "CLOSED_TRANSFER_PENDING")));
        } else if (PENDING.equals(status)) {
          conditions.add(cb.not(expired));
        }
      }
      if (!value.isEmpty()) {
        List<Predicate> matches = new ArrayList<>();
        matches.add(cb.like(cb.lower(root.get("guestName")), like, '!'));
        matches.add(cb.like(cb.upper(root.get("code")), codeLike, '!'));
        if (!phoneValue.isBlank()) {
          var digits = cb.function("regexp_replace", String.class, root.get("guestPhone"), cb.literal("[^0-9]"), cb.literal(""));
          var canonical = cb.<String>selectCase().when(cb.and(cb.like(digits, "84%"), cb.equal(cb.length(digits), 11)),
              cb.concat("0", cb.substring(digits, 3))).otherwise(digits);
          matches.add(cb.like(canonical, phoneLike, '!'));
        }
        conditions.add(cb.or(matches.toArray(Predicate[]::new)));
      }
      return cb.and(conditions.toArray(Predicate[]::new));
    };
    var sort = date != null || "upcoming".equals(scope) ? Sort.by("reservationDate", "reservationTime", "id")
        : Sort.by(Sort.Direction.DESC, "reservationDate", "reservationTime", "id");
    return reservations.findAll(spec, PageRequest.of(Math.clamp(page, 0, Integer.MAX_VALUE / RESERVATIONS_PAGE_SIZE),
        RESERVATIONS_PAGE_SIZE, sort));
  }

  private Predicate startsAfter(Root<Reservation> root, CriteriaBuilder cb, LocalDateTime time) {
    var date = root.<LocalDate>get("reservationDate");
    return cb.or(cb.greaterThan(date, time.toLocalDate()),
        cb.and(cb.equal(date, time.toLocalDate()), cb.greaterThan(root.<LocalTime>get("reservationTime"), time.toLocalTime())));
  }

  private Predicate depositDeadlinePassed(Root<Reservation> root, CriteriaBuilder cb, String status,
      String deadline, LocalDateTime now) {
    var due = root.<LocalDateTime>get(deadline);
    return cb.and(cb.equal(root.get("depositStatus"), status), cb.isNotNull(due), cb.lessThanOrEqualTo(due, now));
  }

  @Transactional(readOnly = true)
  public Map<Long, ReservationRow> reservationRows(List<Reservation> bookings, boolean shiftOpen, LocalDateTime now) {
    if (bookings.isEmpty()) return Map.of();
    var ids = bookings.stream().filter(booking -> booking.getDepositAmount().signum() > 0 || booking.getDepositPaid().signum() > 0)
        .map(Reservation::getId).toList();
    var latest = ids.isEmpty() ? Map.<Long, PaymentIntent>of()
        : intents.latestDeposits(ids).stream().collect(Collectors.toMap(PaymentIntent::getReservationId, intent -> intent));
    var rules = schedule.rules();
    return bookings.stream().collect(Collectors.toMap(Reservation::getId,
        booking -> reservationRow(booking, shiftOpen, now, latest.get(booking.getId()), rules)));
  }

  @Transactional(readOnly = true)
  public Map<Long, ReservationRow> reservationRowUpdates(List<Long> ids, boolean shiftOpen, LocalDateTime now) {
    var pageIds = ids.stream().distinct().limit(RESERVATIONS_PAGE_SIZE).toList();
    return reservationRows(reservations.findAllById(pageIds), shiftOpen, now);
  }

  private ReservationRow reservationRow(Reservation booking, boolean shiftOpen, LocalDateTime now,
      PaymentIntent intent, ReservationSchedule.Rules rules) {
    var actions = actions(booking, now, shiftOpen, rules);
    boolean expired = booking.hasExpiredDeposit(now);
    boolean review = intent != null && PaymentIntent.REVIEW_REQUIRED.equals(intent.getStatus());
    String depositStatus = expired ? "EXPIRED" : review ? "PAYMENT_REVIEW_REQUIRED" : booking.getDepositStatus();
    String depositLabel = expired ? "Đã hết hạn thanh toán cọc" : review ? "Cần đối soát tiền cọc" : booking.getDepositStatusLabel();
    return new ReservationRow(booking.getStatus(), booking.getStatusLabel(), depositStatus, depositLabel,
        booking.getDepositAmount(), booking.getDepositPaid(),
        booking.isOpenBooking() && !expired ? booking.getDepositRemaining() : BigDecimal.ZERO, actions.late(),
        new ListAction(actions.confirm() && !expired, actions.confirmEnabled() && !expired && !review,
            review ? "Cần đối soát giao dịch cọc trước khi xác nhận." : actions.confirmReason()),
        new ListAction(actions.receive() && !expired, actions.receiveEnabled() && !expired, actions.receiveReason()));
  }

  private String like(String value) {
    return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  @Transactional(readOnly = true)
  public List<ReservationEvent> history(Long id) { return events.findByReservation_IdOrderByIdDesc(id); }

  public BookingActions actions(Reservation booking, boolean shiftOpen) {
    return actions(booking, LocalDateTime.now(schedule.getClock()), shiftOpen, schedule.rules());
  }

  private BookingActions actions(Reservation booking, LocalDateTime now, boolean shiftOpen, ReservationSchedule.Rules rules) {
    boolean pending = PENDING.equals(booking.getStatus());
    boolean confirmed = CONFIRMED.equals(booking.getStatus());
    boolean checkedIn = CHECKED_IN.equals(booking.getStatus());
    boolean serving = SEATED.equals(booking.getStatus());
    boolean future = booking.getStartsAt().isAfter(now);
    boolean serviceHours = !now.toLocalTime().isBefore(rules.opening()) && now.toLocalTime().isBefore(rules.closing());
    boolean insideNoShow = now.isBefore(booking.getStartsAt().plusMinutes(rules.noShow()));
    boolean confirm = pending && (future || insideNoShow);
    boolean confirmEnabled = confirm && (!paymentIntents.usesQr() || !booking.isDepositOutstanding());
    String confirmReason = paymentIntents.usesQr() && booking.isDepositOutstanding() ? "Chưa xác minh đủ tiền cọc."
        : !confirm ? "Đã qua thời hạn xác nhận của lượt đặt này." : "";
    boolean receive = confirmed || checkedIn;
    boolean sameDay = booking.getReservationDate().equals(now.toLocalDate());
    var checkInAt = booking.getStartsAt().minusMinutes(rules.checkIn());
    boolean outsideWindow = now.isBefore(checkInAt) || !insideNoShow || !serviceHours;
    boolean receiveEnabled = receive && sameDay && !booking.isDepositOutstanding() && shiftOpen;
    String receiveReason = !sameDay ? "Chỉ nhận bàn trong ngày đặt."
        : !shiftOpen ? "Cần mở ca làm trước khi nhận khách."
        : booking.isDepositOutstanding() ? "Chưa xác minh đủ tiền cọc."
        : outsideWindow ? "Ngoài khung giờ nhận dự kiến. Khách đặt lúc "
            + booking.getStartsAt().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            + ". Bạn có muốn nhận bàn ngay?" : "";
    boolean late = (pending || confirmed) && booking.getStartsAt().isBefore(now);
    return new BookingActions(confirm, confirmEnabled, confirmReason, pending || confirmed || checkedIn,
        receive, receiveEnabled, receiveReason, serving, confirmed && !insideNoShow, late,
        ((pending || confirmed) && future) || checkedIn || serving, pending || confirmed);
  }
}
