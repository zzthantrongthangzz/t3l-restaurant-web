package t3l.restaurant.t3l_restaurant.service;

import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;
import t3l.restaurant.t3l_restaurant.repository.RestaurantTableRepository;

@Service
public class ReservationService {
  private final ReservationRepository reservations;
  private final RestaurantTableRepository tables;
  private final ReservationSchedule schedule;
  private final Validator validator;

  public ReservationService(ReservationRepository reservations, RestaurantTableRepository tables,
      ReservationSchedule schedule, Validator validator) {
    this.reservations = reservations;
    this.tables = tables;
    this.schedule = schedule;
    this.validator = validator;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Reservation create(ReservationForm form) {
    // Validate here as well as in MVC so all callers use the same booking rules.
    validator.validate(form).stream().findFirst().ifPresent(violation -> {
      throw new BookingException(violation.getPropertyPath().toString(), violation.getMessage());
    });
    LocalDateTime end = schedule.validateAndGetEnd(form);
    LocalDateTime start = LocalDateTime.of(form.getDate(), form.getTime());
    String preferred = form.getPreferredArea() == null ? "" : form.getPreferredArea().trim();

    // Locks are held until the booking commits. Status updates acquire the same locks first.
    RestaurantTable assigned = selectTable(tables.lockActiveTables(), start, end,
        form.getGuests(), preferred, null);

    String code = "T3L-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
    Reservation booking = new Reservation(code, form.getGuestName().trim(), form.getGuestPhone().trim(),
        form.getGuestEmail() == null ? null : form.getGuestEmail().trim(), assigned,
        form.getDate(), form.getTime(), form.getGuests(),
        form.getNote() == null ? null : form.getNote().trim(), form.isConsentAccepted());
    booking.setBookingDetails(end, preferred.isEmpty() ? null : preferred);
    return reservations.saveAndFlush(booking);
  }

  // Callers must hold the allocation locks for the lifetime of their transaction.
  RestaurantTable selectTable(List<RestaurantTable> activeTables, LocalDateTime start,
      LocalDateTime end, int guests, String preferred, Long excludedReservationId) {
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
    List<Reservation> blocking = findBlocking(start, end);
    return candidates.stream()
        .filter(table -> blocking.stream().noneMatch(r -> !Objects.equals(r.getId(), excludedReservationId) && r.getTable() != null
            && Objects.equals(r.getTable().getId(), table.getId())))
        .findFirst().orElseThrow(() -> new BookingException(null,
            "Rất tiếc, không còn bàn phù hợp trong khung giờ này. Vui lòng chọn giờ hoặc ngày khác."));

  }

  private List<Reservation> findBlocking(LocalDateTime start, LocalDateTime end) {
    // Include the previous date for legacy bookings that run past midnight.
    return reservations.findByReservationDateBetweenAndStatusNot(
            start.toLocalDate().minusDays(1), end.toLocalDate(), "CANCELLED").stream()
        .filter(r -> r.getStartsAt().isBefore(end)
            && r.effectiveEndsAt(schedule.getDurationMinutes()).isAfter(start))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<Reservation> findRecent() {
    return reservations.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
  }

  @Transactional(readOnly = true)
  public Reservation findByCode(String code) {
    return reservations.findByCode(code).orElse(null);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void updateStatus(Long id, String status) {
    if (!"CONFIRMED".equals(status) && !"CANCELLED".equals(status)) {
      throw new IllegalArgumentException("Trạng thái không hợp lệ.");
    }
    tables.lockActiveTables();
    Reservation booking = reservations.lockById(id)
        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy yêu cầu đặt bàn."));
    if (status.equals(booking.getStatus())) return;
    if ((!"PENDING_CONFIRMATION".equals(booking.getStatus()) && !"CONFIRMED".equals(booking.getStatus()))
        || !booking.getStartsAt().isAfter(LocalDateTime.now(schedule.getClock()))) {
      throw new IllegalArgumentException("Đặt bàn đã kết thúc, đã hủy hoặc hết hiệu lực; không thể sửa trạng thái.");
    }
    if ("CONFIRMED".equals(status)) {
      if (!"PENDING_CONFIRMATION".equals(booking.getStatus())) {
        throw new IllegalArgumentException("Chỉ có thể xác nhận yêu cầu đang chờ. Yêu cầu đã hủy không thể khôi phục.");
      }
      RestaurantTable table = booking.getTable();
      if (table == null || !table.isActive() || table.getCapacity() < booking.getGuestCount()) {
        throw new IllegalArgumentException("Bàn được phân không còn phù hợp. Vui lòng liên hệ khách để đặt lại.");
      }
      boolean conflict = findBlocking(booking.getStartsAt(), booking.effectiveEndsAt(schedule.getDurationMinutes()))
          .stream().anyMatch(other -> !Objects.equals(other.getId(), id) && other.getTable() != null
              && Objects.equals(other.getTable().getId(), table.getId()));
      if (conflict) {
        throw new IllegalArgumentException("Bàn có lịch đặt trùng giờ. Vui lòng liên hệ khách để chọn khung giờ khác.");
      }
    }
    booking.updateStatus(status);
  }
}
