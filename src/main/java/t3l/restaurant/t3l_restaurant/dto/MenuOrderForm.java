package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import t3l.restaurant.t3l_restaurant.entity.DiningOrderItem;

@Getter
@Setter
public class MenuOrderForm {
  @NotNull
  @Size(min = 1, max = 30, message = "Vui lòng chọn từ 1 đến 30 món.")
  private List<@NotNull @Valid Item> items = new ArrayList<>();

  @Getter
  @Setter
  public static class Item {
    @NotNull(message = "Vui lòng chọn món.")
    @Positive(message = "Món đã chọn không hợp lệ.")
    private Long dishId;

    @NotNull(message = "Vui lòng nhập số lượng.")
    @Min(value = 1, message = "Số lượng mỗi món phải từ 1 đến 100.")
    @Max(value = 100, message = "Số lượng mỗi món phải từ 1 đến 100.")
    private Integer quantity;

    @Size(max = DiningOrderItem.MAX_NOTE_LENGTH, message = "Ghi chú món không được vượt quá 120 ký tự.")
    @Pattern(regexp = "[^\\r\\n]*", message = "Ghi chú món chỉ gồm một dòng.")
    private String note;
  }
}
