package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PreorderForm {
  public static final int MAX_DISHES = 30;
  public static final int MAX_QUANTITY = 20;

  @Size(max = MAX_DISHES, message = "Chỉ có thể chọn tối đa 30 loại món.")
  private Map<Long, Integer> preorderQuantities = new LinkedHashMap<>();

  @Size(max = 500, message = "Ghi chú món đặt trước không vượt quá 500 ký tự.")
  private String preorderNote;

  @Digits(integer = 16, fraction = 0, message = "Tổng món đặt trước không hợp lệ.")
  private BigDecimal preorderExpectedTotal;

  @Size(max=32, message="Mã voucher không vượt quá 32 ký tự.")
  private String voucherCode;
}
