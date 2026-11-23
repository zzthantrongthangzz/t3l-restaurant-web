package t3l.restaurant.t3l_restaurant.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;
import t3l.restaurant.t3l_restaurant.repository.RestaurantTableRepository;

@Service
@RequiredArgsConstructor
public class PublicTableBoardService {
  private final RestaurantTableRepository tables;
  private final ReservationRepository reservations;

  @Transactional(readOnly = true)
  public List<TableView> forDate(LocalDate date) {
    var bookings = reservations.findByReservationDateBetweenAndStatusNot(date, date, Reservation.CANCELLED)
        .stream()
        .filter(booking -> booking.getTable() != null && !booking.isTerminal())
        .collect(Collectors.groupingBy(booking -> booking.getTable().getId()));

    return tables.findByActiveTrueOrderByCodeAsc().stream()
        .map(table -> view(table, bookings.getOrDefault(table.getId(), List.of())))
        .toList();
  }

  private TableView view(RestaurantTable table, List<Reservation> bookings) {
    if (bookings.isEmpty()) return new TableView(table, "FREE", "Trống");
    if (bookings.stream().anyMatch(booking -> Reservation.PENDING.equals(booking.getStatus()))) {
      return new TableView(table, "PENDING", "Đang chờ xác nhận");
    }
    return new TableView(table, "BOOKED", "Đã đặt");
  }

  public record TableView(RestaurantTable table, String state, String label) {
    public boolean isVip() {
      return table.getTableType() != null && table.getTableType().toLowerCase().contains("vip");
    }
  }
}
