package t3l.restaurant.t3l_restaurant.service;

import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
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
  private final StaffShiftService shifts;
  private final RestaurantSectionRepository sections;
  private final AdminAudit audit;

  public ReservationService(ReservationRepository reservations, RestaurantTableRepository tables,
      ReservationSchedule schedule, Validator validator, ReservationEmailQueue emails,
      ReservationEventRepository events, DiningOrderRepository orders, StaffShiftService shifts,
      RestaurantSectionRepository sections, AdminAudit audit) {
    this.reservations = reservations;
    this.tables = tables;
    this.schedule = schedule;
    this.validator = validator;
    this.emails = emails;
    this.events = events;
    this.orders = orders;
    this.shifts = shifts;
    this.sections = sections;
    this.audit = audit;
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
  public Reservation createStaffBooking(ReservationForm form, Long tableId, boolean walkIn, String actor) {
    return createBooking(form, null, actor, tableId, walkIn);
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
      return !(actor != null && "consentAccepted".equals(field))
          && !(walkIn && "guestPhone".equals(field) && (form.getGuestPhone() == null || form.getGuestPhone().isBlank()));
    }).findFirst().ifPresent(violation -> {
      throw new BookingException(violation.getPropertyPath().toString(), violation.getMessage());
    });
    List<RestaurantTable> active = tables.lockActiveTables();
    LocalDateTime end = walkIn ? serviceEnd(form.getDate().atTime(form.getTime())) : schedule.validateAndGetEnd(form);
    LocalDateTime start = LocalDateTime.of(form.getDate(), form.getTime());
    String preferred = form.getPreferredArea() == null ? "" : form.getPreferredArea().trim();

    // Locks are held until the booking commits. Status updates acquire the same locks first.
    RestaurantTable assigned = tableId == null
        ? selectTable(active, start, end, form.getGuests(), preferred, null, walkIn)
        : requireTable(active, tableId, start, end, form.getGuests(), null, walkIn);

    String code = "T3L-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
    Reservation booking = new Reservation(code, form.getGuestName().trim(), form.getGuestPhone() == null ? "" : form.getGuestPhone().trim(),
        form.getGuestEmail() == null ? null : form.getGuestEmail().trim(), assigned,
        form.getDate(), form.getTime(), form.getGuests(),
        form.getNote() == null ? null : form.getNote().trim(), form.isConsentAccepted());
    booking.setBookingDetails(end, preferred.isEmpty() ? null : preferred);
    booking.assignCustomer(customer);
    if (actor != null) {
      booking.updateStatus(CONFIRMED);
      if (walkIn) booking.checkIn(start);
    }
    Reservation saved = reservations.saveAndFlush(booking);
    if (actor == null) emails.enqueue(saved, Kind.RECEIVED, null, null);
    else {
      record(saved, actor, walkIn ? "Tiếp nhận khách đến trực tiếp." : "Tạo và xác nhận đặt bàn qua nhân viên.");
      if (!walkIn) emails.enqueue(saved, Kind.CONFIRMED, null, null);
    }
    return saved;
  }

  // Callers must hold the allocation locks for the lifetime of their transaction.
  RestaurantTable selectTable(List<RestaurantTable> activeTables, LocalDateTime start,
      LocalDateTime end, int guests, String preferred, Long excludedReservationId) {
    return selectTable(activeTables, start, end, guests, preferred, excludedReservationId, false);
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

  private List<Reservation> findBlocking(LocalDateTime start, LocalDateTime end, boolean immediate) {
    int duration = schedule.getDurationMinutes();
    // Include the previous date for legacy bookings that run past midnight.
    var matches = new ArrayList<>(reservations.findByReservationDateBetweenAndStatusNot(
            start.toLocalDate().minusDays(1), end.toLocalDate(), CANCELLED).stream()
        .filter(r -> !r.isTerminal() && !SEATED.equals(r.getStatus()) && !(immediate && CHECKED_IN.equals(r.getStatus())))
        .filter(r -> r.getStartsAt().isBefore(end)
            && r.effectiveEndsAt(duration).isAfter(start))
        .toList());
    reservations.findByStatusInAndTableIsNotNull(immediate ? List.of(SEATED, CHECKED_IN) : List.of(SEATED)).stream()
        .filter(r -> immediate || ((r.getSeatedAt() == null ? r.getStartsAt() : r.getSeatedAt()).isBefore(end)
            && r.effectiveEndsAt(duration).isAfter(start)))
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
    if ((!booking.isOpenBooking() && !cancelArrived)
        || ((actor == null || CONFIRMED.equals(status)) && !booking.getStartsAt().isAfter(now()))) {
      throw new IllegalArgumentException("Đặt bàn đã kết thúc, đã hủy hoặc hết hiệu lực; không thể sửa trạng thái.");
    }
    if (CONFIRMED.equals(status)) requireTable(active, tableId(booking), booking.getStartsAt(),
        booking.effectiveEndsAt(schedule.getDurationMinutes()), booking.getGuestCount(), booking.getId(), false);
    Kind kind = CONFIRMED.equals(status) ? Kind.CONFIRMED : PENDING.equals(booking.getStatus()) ? Kind.REJECTED : Kind.CANCELLED;
    booking.updateStatus(status);
    record(booking, actor, booking.getStatusLabel() + ".");
    emails.enqueue(booking, kind, null, null);
  }

  @PreAuthorize("hasAnyRole('STAFF', 'ADMIN') and #actor == authentication.name")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void staffStatus(Long id, String status, String actor) {
    var active = tables.lockActiveTables();
    var booking = lockBooking(id);
    if (booking.getStatus().equals(status)) return;
    LocalDateTime now = now();
    switch (status) {
      case CONFIRMED, CANCELLED -> decideBooking(booking, status, active, actor);
      case CHECKED_IN -> {
        if (!CONFIRMED.equals(booking.getStatus()) || !booking.getReservationDate().equals(now.toLocalDate())
            || now.isBefore(booking.getStartsAt().minusMinutes(schedule.getCheckInMinutes())) || !inServiceHours(now)) {
          throw new BookingException(null, "Chỉ check-in lịch đã xác nhận trong giờ phục vụ, từ "
              + schedule.getCheckInMinutes() + " phút trước giờ đặt.");
        }
        booking.checkIn(now);
      }
      case SEATED -> {
        if (!CHECKED_IN.equals(booking.getStatus())) throw new BookingException(null, "Hãy check-in khách trước khi nhận bàn.");
        if (!inServiceHours(now)) throw new BookingException("time", "Chỉ nhận bàn trong giờ phục vụ của nhà hàng.");
        LocalDateTime end = expectedServiceEnd(booking, now);
        requireTable(active, tableId(booking), now, end, booking.getGuestCount(), id, true);
        booking.seat(shifts.requireOpenShiftForUpdate(actor), now, end);
      }
      case COMPLETED -> {
        if (!SEATED.equals(booking.getStatus())) throw new BookingException(null, "Chỉ kết thúc lượt đang phục vụ.");
        orders.findByReservation_Id(id).ifPresent(order -> {
          if (DiningOrder.DRAFT.equals(order.getStatus()) && !order.hasItems()) order.voidEmptyDraft();
          else if (!DiningOrder.PAID.equals(order.getStatus())) {
            throw new BookingException(null, "Hãy thanh toán hóa đơn trước khi kết thúc phục vụ.");
          }
        });
        booking.complete(now);
      }
      case NO_SHOW -> {
        if (!CONFIRMED.equals(booking.getStatus()) || now.isBefore(booking.getStartsAt().plusMinutes(schedule.getNoShowMinutes()))) {
          throw new BookingException(null, "Chỉ đánh dấu khách không đến sau giờ đặt ít nhất " + schedule.getNoShowMinutes() + " phút.");
        }
        booking.updateStatus(NO_SHOW);
        emails.enqueue(booking, Kind.NO_SHOW, null, null);
      }
      default -> throw new BookingException(null, "Trạng thái không hợp lệ.");
    }
    if (!CONFIRMED.equals(status) && !CANCELLED.equals(status)) record(booking, actor, booking.getStatusLabel() + ".");
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
    LocalDateTime end = !immediate ? booking.effectiveEndsAt(schedule.getDurationMinutes())
        : !inServiceHours(now) && SEATED.equals(booking.getStatus()) ? now.plusMinutes(schedule.getSlotMinutes())
        : expectedServiceEnd(booking, now);
    RestaurantTable destination = requireTable(active, tableId, immediate ? now : booking.getStartsAt(),
        end, booking.getGuestCount(), id, immediate);
    String from = booking.getTable() == null ? "chưa phân bàn" : booking.getTable().getCode();
    booking.moveTo(destination, end);
    record(booking, actor, "Chuyển bàn: " + from + " → " + destination.getCode() + ".");
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
            .anyMatch(booking -> booking.effectiveEndsAt(duration).isAfter(now));
  }

  private LocalDateTime expectedServiceEnd(Reservation booking, LocalDateTime now) {
    var planned = booking.effectiveEndsAt(schedule.getDurationMinutes());
    var closing = now.toLocalDate().atTime(schedule.getClosingTime());
    if (planned.isAfter(now) && !planned.isAfter(closing)) return planned;
    return serviceEnd(now);
  }

  private LocalDateTime serviceEnd(LocalDateTime now) {
    LocalDateTime closing = now.toLocalDate().atTime(schedule.getClosingTime());
    if (!inServiceHours(now)) {
      throw new BookingException("time", "Chỉ nhận bàn trực tiếp trong giờ phục vụ của nhà hàng.");
    }
    LocalDateTime planned = now.plusMinutes(schedule.getDurationMinutes());
    return planned.isBefore(closing) ? planned : closing;
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
