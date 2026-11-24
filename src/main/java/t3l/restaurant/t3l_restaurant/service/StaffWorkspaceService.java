package t3l.restaurant.t3l_restaurant.service;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import t3l.restaurant.t3l_restaurant.service.PublicTableBoardService.TableState;

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

  public record TableView(RestaurantTable table, TableState state, Reservation current) {}
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
    var board = boardAt(now, now.plusMinutes(1), 1).values().stream().flatMap(List::stream).toList();
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
  public Map<String, List<TableView>> board(LocalDate date, LocalTime time, int guests, int duration) {
    var start = time == null ? LocalDateTime.now(schedule.getClock()) : date.atTime(time);
    return boardAt(start, start.plusMinutes(duration), guests);
  }

  private Map<String, List<TableView>> boardAt(LocalDateTime start, LocalDateTime end, int guests) {
    var blocked = bookings.blockingByTable(start, end);
    Map<String, List<TableView>> board = new LinkedHashMap<>();
    var activeIds = new HashSet<>(tables.activeIds());
    for (RestaurantTable table : tables.findAllByOrderByTableTypeAscCodeAsc()) {
      var current = blocked.get(table.getId());
      var state = activeIds.contains(table.getId()) ? PublicTableBoardService.stateOf(table, current, guests) : TableState.INACTIVE;
      board.computeIfAbsent(table.getTableType(), key -> new ArrayList<>()).add(new TableView(table, state, current));
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
    var now = LocalDateTime.now(schedule.getClock());
    boolean pending = PENDING.equals(booking.getStatus());
    boolean confirmed = CONFIRMED.equals(booking.getStatus());
    boolean checkedIn = CHECKED_IN.equals(booking.getStatus());
    boolean serving = SEATED.equals(booking.getStatus());
    boolean future = booking.getStartsAt().isAfter(now);
    boolean serviceHours = !now.toLocalTime().isBefore(schedule.getOpeningTime()) && now.toLocalTime().isBefore(schedule.getClosingTime());
    return new BookingActions(pending && future, pending || confirmed || checkedIn,
        confirmed && !booking.isDepositOutstanding() && serviceHours && booking.getReservationDate().equals(now.toLocalDate()) && !now.isBefore(booking.getStartsAt().minusMinutes(schedule.getCheckInMinutes())),
        checkedIn && serviceHours, serving, confirmed && !now.isBefore(booking.getStartsAt().plusMinutes(schedule.getNoShowMinutes())),
        ((pending || confirmed) && future) || checkedIn || serving, pending || confirmed);
  }
}
