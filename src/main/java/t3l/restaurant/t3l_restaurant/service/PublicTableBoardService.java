package t3l.restaurant.t3l_restaurant.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;
import t3l.restaurant.t3l_restaurant.repository.RestaurantTableRepository;

@Service
@RequiredArgsConstructor
public class PublicTableBoardService {
  private final RestaurantTableRepository tables;
  private final ReservationService reservations;

  @Transactional(readOnly = true)
  public List<TableView> forSlot(LocalDate date, LocalTime time, int guests, int duration) {
    if (time == null) return List.of();
    var start = date.atTime(time);
    var bookings = reservations.blockingByTable(start, start.plusMinutes(duration));
    return tables.findByActiveTrueOrderByCodeAsc().stream()
        .map(table -> {
          var state = stateOf(table, bookings.get(table.getId()), guests);
          return new TableView(table, state.name(), state.getLabel());
        })
        .toList();
  }

  public enum TableState {
    FREE("Trống"), PENDING("Đang chờ xác nhận"), RESERVED("Đã đặt"), OCCUPIED("Đang phục vụ"),
    INACTIVE("Tạm ngưng"), TOO_SMALL("Không đủ chỗ");

    private final String label;
    TableState(String label) { this.label = label; }
    public String getLabel() { return label; }
  }

  static TableState stateOf(RestaurantTable table, Reservation booking, int guests) {
    if (booking != null) {
      return switch (booking.getStatus()) {
        case Reservation.PENDING -> TableState.PENDING;
        case Reservation.SEATED -> TableState.OCCUPIED;
        default -> TableState.RESERVED;
      };
    }
    return table.getCapacity() < guests ? TableState.TOO_SMALL : TableState.FREE;
  }

  public record TableView(RestaurantTable table, String state, String label) {
    public boolean isVip() {
      return table.getTableType() != null && table.getTableType().toLowerCase().contains("vip");
    }
  }
}
