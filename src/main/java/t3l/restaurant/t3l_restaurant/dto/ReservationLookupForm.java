package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReservationLookupForm {
  @NotBlank(message = "Vui lòng nhập mã đặt bàn.")
  @Pattern(regexp = "T3L-[A-Z0-9-]{4,16}", message = "Mã đặt bàn không hợp lệ.")
  private String code;

  public void setCode(String code) {
    this.code = code == null ? null : code.strip().toUpperCase(java.util.Locale.ROOT);
  }

  @NotBlank(message = "Vui lòng nhập số điện thoại đã đặt bàn.")
  @Size(max = 30, message = "Số điện thoại không hợp lệ.")
  @Pattern(regexp = "(?=(?:\\D*\\d){8,15}\\D*$)\\+?[0-9][0-9() .-]*[0-9]",
      message = "Số điện thoại không hợp lệ.")
  private String phone;

  public void setPhone(String phone) {
    this.phone = phone == null ? null : phone.strip();
  }
}
