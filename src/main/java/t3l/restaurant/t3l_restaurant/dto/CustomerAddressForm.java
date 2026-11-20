package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CustomerAddressForm {
  @NotBlank(message = "Vui lòng nhập tên người nhận.")
  @Size(max = 120, message = "Tên người nhận không vượt quá 120 ký tự.")
  private String recipientName;

  @NotBlank(message = "Vui lòng nhập số điện thoại.")
  @Size(max = 30, message = "Số điện thoại không vượt quá 30 ký tự.")
  @Pattern(regexp = "(?=(?:[^0-9]*[0-9]){8,15}[^0-9]*$)\\+?[0-9 () .-]+",
      message = "Số điện thoại cần có từ 8 đến 15 chữ số.")
  private String phone;

  @NotBlank(message = "Vui lòng nhập địa chỉ.")
  @Size(max = 300, message = "Địa chỉ không vượt quá 300 ký tự.")
  private String streetAddress;

  private boolean defaultAddress;
}
