package t3l.restaurant.t3l_restaurant.service;

import jakarta.persistence.criteria.Predicate;
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
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.ReservationEvent;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;
import t3l.restaurant.t3l_restaurant.repository.ReservationChangeRequestRepository;
import t3l.restaurant.t3l_restaurant.repository.DiningOrderRepository;
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
  private final ReservationRepository reservations;
  private final RestaurantTableRepository tables;
  private final ReservationEventRepository events;
  private final ReservationChangeRequestRepository requests;
  private final DiningOrderRepository orders;
  private final ReservationSchedule schedule;
  private final ReservationService bookings;
  private final PaymentIntentService paymentIntents;

  public enum TableState {
    FREE("Bàn trống"), PENDING("Đợi xác nhận"), RESERVED("Đã xác nhận"), OCCUPIED("Đang phục vụ"), INACTIVE("Tạm ngưng"), TOO_SMALL("Không đủ chỗ");

    private final String label;
    TableState(String label) { this.label = label; }
    public String getLabel() { return label; }
  }

  public record TableView(RestaurantTable table, TableState state, Reservation current,
                          List<Reservation> otherBookings, BookingActions actions, boolean canOrder, boolean canWalkIn,
                          Reservation nextBooking, LocalDateTime servingUntil, boolean serviceAtRisk) {}
  public record Dashboard(LocalDate date, long bookingsToday, long pending, long guestsArrived, long serving,
                          long free, long changeRequests, long awaitingPayment, List<Reservation> upcoming, List<Reservation> toConfirm,
                          List<Reservation> checkedIn) {}
  public record BookingActions(boolean confirm, boolean confirmEnabled, String confirmReason,
                               boolean cancel, boolean receive, boolean receiveEnabled, String receiveReason,
                               boolean complete, boolean noShow, boolean late, boolean moveTable, boolean reschedule) {}

  @Transactional(readOnly = true)
  public Dashboard dashboard() {
    LocalDateTime now = LocalDateTime.now(schedule.getClock());
    LocalDate today = now.toLocalDate();
    var bookings = reservations.findByReservationDateOrderByReservationTimeAscIdAsc(today);
    var board = boardAt(today, now, null, 1, false);
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
    return boardAt(date, LocalDateTime.now(schedule.getClock()), time, guests, shiftOpen);
  }

  private List<TableView> boardAt(LocalDate date, LocalDateTime now, LocalTime time, int guests, boolean shiftOpen) {
    var blocked = time == null ? Map.<Long, Reservation>of() : bookings.blockingByTable(
        date.atTime(time), date.atTime(time).plusMinutes(schedule.getDurationMinutes()));
    var scheduledByTable = reservations.findByReservationDateBetweenAndStatusNot(date, date, CANCELLED)
        .stream().filter(booking -> booking.getTable() != null && !booking.hasExpiredDeposit(now) && ReservationService.BLOCKING_STATUSES.contains(booking.getStatus()))
        .sorted(Comparator.comparing(Reservation::getStartsAt))
        .collect(Collectors.groupingBy(booking -> booking.getTable().getId()));
    var arrivedByTable = date.equals(now.toLocalDate())
        ? reservations.findByStatusInAndTableIsNotNull(List.of(SEATED, CHECKED_IN)).stream()
            .collect(Collectors.groupingBy(booking -> booking.getTable().getId()))
        : Map.<Long, List<Reservation>>of();
    var walkInEnd = now.plusMinutes(schedule.getDurationMinutes());
    var walkInBlocks = date.equals(now.toLocalDate())
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
      Reservation current = arrived.stream().min(Comparator.comparing(booking -> !SEATED.equals(booking.getStatus())))
          .orElseGet(() -> scheduled.stream()
              .filter(booking -> !booking.getStartsAt().isBefore(now) || booking.effectiveEndsAt(schedule.getDurationMinutes()).isAfter(now))
              .findFirst().orElse(null));
      if (time != null) current = blocked.get(table.getId());
      Reservation selected = current;
      var others = scheduled.stream().filter(booking -> selected == null || !Objects.equals(booking.getId(), selected.getId())).toList();
      TableState state = !activeIds.contains(table.getId()) ? TableState.INACTIVE : current == null ? (table.getCapacity() >= guests ? TableState.FREE : TableState.TOO_SMALL)
          : switch (current.getStatus()) {
            case SEATED -> TableState.OCCUPIED;
            case PENDING -> TableState.PENDING;
            default -> TableState.RESERVED;
          };
      boolean canWalkIn = date.equals(now.toLocalDate()) && activeIds.contains(table.getId())
          && table.getCapacity() >= guests && arrived.isEmpty() && !walkInBlocks.containsKey(table.getId());
      var next = scheduled.stream().filter(booking -> SCHEDULED.contains(booking.getStatus())
          && booking.effectiveEndsAt(schedule.getDurationMinutes()).isAfter(now)).findFirst().orElse(null);
      LocalDateTime servingUntil = state == TableState.OCCUPIED ? current.effectiveEndsAt(schedule.getDurationMinutes()) : null;
      boolean serviceAtRisk = servingUntil != null && next != null
          && (servingUntil.isAfter(next.getStartsAt()) || !now.plusMinutes(schedule.getSlotMinutes()).isBefore(next.getStartsAt()));
      board.add(new TableView(table, state, current, others, current == null ? null : actions(current, now, shiftOpen),
          current != null && bookings.canAddMenuItems(current, boardOrders.get(current.getId()), true), canWalkIn,
          next, servingUntil, serviceAtRisk));
    }
    return board;
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
  public Page<Reservation> searchQuick(LocalDate date, String status, String query, int page) {
    String value = query == null ? "" : query.strip();
    String like = like(value.toLowerCase(Locale.ROOT));
    String codeLike = like(value.toUpperCase(Locale.ROOT));
    String phoneValue = ReservationManagementService.canonicalPhone(value);
    String phoneLike = like(phoneValue);
    Specification<Reservation> spec = (root, criteriaQuery, cb) -> {
      List<Predicate> conditions = new ArrayList<>();
      if (date != null) conditions.add(cb.equal(root.get("reservationDate"), date));
      if (STATUSES.contains(status)) conditions.add(cb.equal(root.get("status"), status));
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
    var sort = date == null ? Sort.by(Sort.Direction.DESC, "reservationDate", "reservationTime", "id")
        : Sort.by("reservationTime", "id");
    return reservations.findAll(spec, PageRequest.of(Math.max(page, 0), 20, sort));
  }

  private String like(String value) {
    return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  @Transactional(readOnly = true)
  public List<ReservationEvent> history(Long id) { return events.findByReservation_IdOrderByIdDesc(id); }

  public BookingActions actions(Reservation booking, boolean shiftOpen) {
    return actions(booking, LocalDateTime.now(schedule.getClock()), shiftOpen);
  }

  private BookingActions actions(Reservation booking, LocalDateTime now, boolean shiftOpen) {
    boolean pending = PENDING.equals(booking.getStatus());
    boolean confirmed = CONFIRMED.equals(booking.getStatus());
    boolean checkedIn = CHECKED_IN.equals(booking.getStatus());
    boolean serving = SEATED.equals(booking.getStatus());
    boolean future = booking.getStartsAt().isAfter(now);
    boolean serviceHours = !now.toLocalTime().isBefore(schedule.getOpeningTime()) && now.toLocalTime().isBefore(schedule.getClosingTime());
    boolean insideNoShow = now.isBefore(booking.getStartsAt().plusMinutes(schedule.getNoShowMinutes()));
    boolean confirm = pending && (future || insideNoShow);
    boolean confirmEnabled = confirm && (!paymentIntents.usesQr() || !booking.isDepositOutstanding());
    String confirmReason = paymentIntents.usesQr() && booking.isDepositOutstanding() ? "Chưa xác minh đủ tiền cọc."
        : !confirm ? "Đã qua thời hạn xác nhận của lượt đặt này." : "";
    boolean receive = confirmed || checkedIn;
    boolean sameDay = booking.getReservationDate().equals(now.toLocalDate());
    var checkInAt = booking.getStartsAt().minusMinutes(schedule.getCheckInMinutes());
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
