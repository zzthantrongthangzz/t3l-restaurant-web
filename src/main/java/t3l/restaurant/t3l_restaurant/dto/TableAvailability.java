package t3l.restaurant.t3l_restaurant.dto;

import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;

public record TableAvailability(RestaurantTable table, String status, String guestName) {
  public boolean isAvailable() {
    return status.equals("AVAILABLE");
  }
}
