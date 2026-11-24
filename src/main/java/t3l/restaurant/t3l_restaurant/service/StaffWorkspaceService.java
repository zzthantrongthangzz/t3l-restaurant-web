package t3l.restaurant.t3l_restaurant.service;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

  public enum TableState {
    FREE("Bàn trống"), PENDING("Đợi xác nhận"), RESERVED("Đã xác nhận"), OCCUPIED("Đang phục vụ"), INACTIVE("Tạm ngưng");

    private final String label;
    TableState(String label) { this.label = label; }
    public String getLabel() { return label; }
  }

  public record TableView(RestaurantTable table, TableState state, Reservation current,
                          List<Reservation> otherBookings, BookingActions actions, boolean canOrder) {}
  public record Dashboard(LocalDate date, long bookingsToday, long pending, long guestsArrived, long serving,
                          long free, long changeRequests, long awaitingPayment, List<Reservation> upcoming, List<Reservation> toConfirm,
                          List<Reservation> checkedIn) {}
  public record BookingActions(boolean confirm, boolean cancel, boolean checkIn, boolean seat, boolean complete,
                               boolean noShow, boolean moveTable, boolean reschedule) {}

  @Transactional(readOnly = true)
  public Dashboard dashboard() {
    LocalDateTime now = LocalDateTime.now(schedule.getClock());
    LocalDate today = now.toLocalDate();
    var bookings = reservations.findByReservationDateOrderByReservationTimeAscIdAsc(today);
    var board = boardAt(today, now);
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
        reservations.findByStatusInAndTableIsNotNull(List.of(CHECKED_IN)).stream()
            .sorted(Comparator.comparing(Reservation::getStartsAt)).limit(8).toList());
  }

  @Transactional(readOnly = true)
  public List<TableView> board(LocalDate date) {
    return boardAt(date, LocalDateTime.now(schedule.getClock()));
  }

  private List<TableView> boardAt(LocalDate date, LocalDateTime now) {
    var scheduledByTable = reservations.findByReservationDateBetweenAndStatusNot(date, date, CANCELLED)
        .stream().filter(booking -> booking.getTable() != null && ReservationService.BLOCKING_STATUSES.contains(booking.getStatus()))
        .sorted(Comparator.comparing(Reservation::getStartsAt))
        .collect(Collectors.groupingBy(booking -> booking.getTable().getId()));
    var arrivedByTable = date.equals(now.toLocalDate())
        ? reservations.findByStatusInAndTableIsNotNull(List.of(SEATED, CHECKED_IN)).stream()
            .collect(Collectors.groupingBy(booking -> booking.getTable().getId()))
        : Map.<Long, List<Reservation>>of();
    var bookingIds = Stream.concat(scheduledByTable.values().stream(), arrivedByTable.values().stream())
        .flatMap(List::stream).map(Reservation::getId).distinct().toList();
    var lockedOrders = bookingIds.isEmpty() ? java.util.Set.<Long>of()
        : orders.findByReservation_IdIn(bookingIds).stream().filter(order -> !DiningOrder.DRAFT.equals(order.getStatus()))
            .map(order -> order.getReservation().getId()).collect(Collectors.toSet());
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
              .findFirst().orElseGet(() -> scheduled.isEmpty() ? null : scheduled.getFirst()));
      var others = scheduled.stream().filter(booking -> current == null || !Objects.equals(booking.getId(), current.getId())).toList();
      TableState state = !activeIds.contains(table.getId()) ? TableState.INACTIVE : current == null ? TableState.FREE
          : switch (current.getStatus()) {
            case SEATED -> TableState.OCCUPIED;
            case PENDING -> TableState.PENDING;
            default -> TableState.RESERVED;
          };
      board.add(new TableView(table, state, current, others, current == null ? null : actions(current, now),
          current != null && !lockedOrders.contains(current.getId())));
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

  private String like(String value) {
    return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  @Transactional(readOnly = true)
  public List<ReservationEvent> history(Long id) { return events.findByReservation_IdOrderByIdDesc(id); }

  public BookingActions actions(Reservation booking) {
    return actions(booking, LocalDateTime.now(schedule.getClock()));
  }

  private BookingActions actions(Reservation booking, LocalDateTime now) {
    boolean pending = PENDING.equals(booking.getStatus());
    boolean confirmed = CONFIRMED.equals(booking.getStatus());
    boolean checkedIn = CHECKED_IN.equals(booking.getStatus());
    boolean serving = SEATED.equals(booking.getStatus());
    boolean future = booking.getStartsAt().isAfter(now);
    boolean serviceHours = !now.toLocalTime().isBefore(schedule.getOpeningTime()) && now.toLocalTime().isBefore(schedule.getClosingTime());
    boolean depositCleared = booking.getDepositAmount().signum() == 0 || "PAID".equals(booking.getDepositStatus());
    return new BookingActions(pending && future, pending || confirmed || checkedIn,
        confirmed && depositCleared && serviceHours && booking.getReservationDate().equals(now.toLocalDate()) && !now.isBefore(booking.getStartsAt().minusMinutes(schedule.getCheckInMinutes())),
        checkedIn && serviceHours, serving, confirmed && !now.isBefore(booking.getStartsAt().plusMinutes(schedule.getNoShowMinutes())),
        ((pending || confirmed) && future) || checkedIn || serving, pending || confirmed);
  }
}
