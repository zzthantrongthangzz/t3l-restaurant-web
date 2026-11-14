package t3l.restaurant.t3l_restaurant.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.dto.TableAvailability;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;
import t3l.restaurant.t3l_restaurant.repository.RestaurantTableRepository;

@Service
public class ReservationService {
  private final ReservationRepository reservations;
  private final RestaurantTableRepository tables;

  public ReservationService(ReservationRepository reservations, RestaurantTableRepository tables) {
    this.reservations = reservations;
    this.tables = tables;
  }

  @Transactional
  public Reservation create(ReservationForm form, RestaurantTable table) {
    if (!isTableAvailable(table.getId(), form.getDate())) {
      throw new IllegalStateException("Bàn đã được đặt trong ngày này. Vui lòng chọn bàn khác.");
    }
    String code = "T3L-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    return reservations.save(
        new Reservation(
            code,
            form.getGuestName(),
            form.getGuestPhone(),
            form.getGuestEmail(),
            table,
            form.getDate(),
            form.getTime(),
            form.getGuests(),
            form.getNote(),
            form.isConsentAccepted()));
  }

  @Transactional(readOnly = true)
  public List<TableAvailability> getTableAvailability(LocalDate date, boolean showGuestNames) {
    List<RestaurantTable> activeTables =
        tables.findByActiveTrueOrderByCodeAsc().stream()
            .sorted(
                Comparator.comparing(
                        (RestaurantTable table) -> !"Phòng VIP".equals(table.getTableType()))
                    .thenComparing(RestaurantTable::getCode))
            .toList();
    Map<Long, Reservation> reservationByTable =
        reservations
            .findByReservationDateAndStatusNot(date, "CANCELLED")
            .stream()
            .filter(reservation -> reservation.getTable() != null)
            .collect(
                Collectors.toMap(
                    reservation -> reservation.getTable().getId(),
                    reservation -> reservation,
                    (first, ignored) -> first));

    return activeTables.stream()
        .map(table -> {
          Reservation reservation = reservationByTable.get(table.getId());
          String status = reservation == null ? "AVAILABLE" : "RESERVED";
          String guestName =
              showGuestNames && reservation != null ? reservation.getGuestName() : null;
          return new TableAvailability(table, status, guestName);
        })
        .toList();
  }

  @Transactional(readOnly = true)
  public boolean isTableAvailable(Long tableId, LocalDate date) {
    return !reservations.existsByTable_IdAndReservationDateAndStatusNot(tableId, date, "CANCELLED");
  }

  @Transactional(readOnly = true)
  public List<Reservation> findRecent() {
    return reservations.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
  }

  @Transactional(readOnly = true)
  public Reservation findByCode(String code) {
    return reservations.findByCode(code).orElse(null);
  }

  @Transactional
  public void updateStatus(Long id, String status) {
    Reservation reservation =
        reservations
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy yêu cầu đặt bàn."));
    if (!"CONFIRMED".equals(status) && !"CANCELLED".equals(status)) {
      throw new IllegalArgumentException("Trạng thái không hợp lệ.");
    }
    reservation.updateStatus(status);
  }
}
