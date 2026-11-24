package t3l.restaurant.t3l_restaurant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;

class DepositPolicyTest {
  private final DepositPolicy policy = new DepositPolicy(120, 120, 1440, 50, 0);

  @Test
  void chargesTheHigherOfTheTableMinimumAndSeventyPercentOfPreorders() {
    assertEquals(BigDecimal.ZERO, policy.required(table("Bàn thường", 4), BigDecimal.ZERO));
    assertEquals(new BigDecimal("70001"), policy.required(table("Bàn thường", 4), new BigDecimal("100001")));
    assertEquals(new BigDecimal("300000"), policy.required(table("Sảnh chính", 9), BigDecimal.ZERO));
    assertEquals(new BigDecimal("500000"), policy.required(table("Phòng VIP", 4), new BigDecimal("100001")));
    assertEquals(new BigDecimal("630000"), policy.required(table("Phòng VIP", 4), new BigDecimal("900000")));
  }

  private RestaurantTable table(String area, int capacity) {
    var table = new RestaurantTable();
    table.configure("QA", area, null, capacity, true);
    return table;
  }
}
