package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CashSaleForm {
  @NotNull private Long dishId;

  @Positive private int quantity = 1;

  @NotNull private String paymentMethod = "CASH";
}
