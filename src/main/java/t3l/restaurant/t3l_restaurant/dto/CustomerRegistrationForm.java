package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CustomerRegistrationForm {
  @NotBlank(message = "Vui lòng nhập họ và tên.")
  @Size(max = 120, message = "Họ và tên không vượt quá 120 ký tự.")
  private String fullName = "";

  @NotBlank(message = "Vui lòng nhập số điện thoại.")
  @Size(max = 30, message = "Số điện thoại không vượt quá 30 ký tự.")
  @Pattern(regexp = "[+0-9(). -]{8,30}", message = "Số điện thoại không đúng định dạng.")
  private String phone = "";

  @NotBlank(message = "Vui lòng nhập mật khẩu.")
  @Size(min = 12, max = 72, message = "Mật khẩu cần dài từ 12 đến 72 ký tự.")
  private String password = "";

  @NotBlank(message = "Vui lòng xác nhận mật khẩu.")
  private String confirmPassword = "";

  public void setFullName(String fullName) {
    this.fullName = fullName == null ? "" : fullName.strip().replaceAll("\\s+", " ");
  }

  public void setPhone(String phone) {
    this.phone = phone == null ? "" : phone.strip();
  }
}
