package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegistrationForm {
  @NotBlank(message = "Vui lòng nhập họ tên.")
  @Size(min = 2, max = 120, message = "Họ tên cần từ 2 đến 120 ký tự.")
  private String fullName;

  @NotBlank(message = "Vui lòng nhập email.")
  @Email(message = "Email không đúng định dạng.")
  @Pattern(regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", message = "Email cần có tên miền hợp lệ.")
  @Size(max = 254, message = "Email không vượt quá 254 ký tự.")
  private String email;

  @NotBlank(message = "Vui lòng nhập số điện thoại.")
  @Size(max = 30, message = "Số điện thoại không vượt quá 30 ký tự.")
  @Pattern(regexp = "(?=(?:[^0-9]*[0-9]){8,15}[^0-9]*$)\\+?[0-9 () .-]+", message = "Số điện thoại cần có từ 8 đến 15 chữ số.")
  private String phone;

  @NotBlank(message = "Vui lòng nhập tên đăng nhập.")
  @Pattern(regexp = "[a-z0-9][a-z0-9._-]{2,39}", message = "Tên đăng nhập cần 3–40 ký tự: chữ không dấu, số, dấu chấm, gạch dưới hoặc gạch ngang; bắt đầu bằng chữ hoặc số.")
  private String username;

  @NotBlank(message = "Vui lòng nhập mật khẩu.")
  @Size(min = 8, max = 72, message = "Mật khẩu cần từ 8 đến 72 ký tự.")
  private String password;

  @NotBlank(message = "Vui lòng xác nhận mật khẩu.")
  @Size(max = 72, message = "Xác nhận mật khẩu không vượt quá 72 ký tự.")
  private String confirmPassword;

  @AssertTrue(message = "Mật khẩu quá dài khi chứa ký tự có dấu hoặc ký tự đặc biệt. Vui lòng dùng mật khẩu ngắn hơn.")
  public boolean isPasswordWithinByteLimit() {
    return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
  }

  @AssertTrue(message = "Xác nhận mật khẩu chưa khớp.")
  public boolean isPasswordsMatching() {
    return Objects.equals(password, confirmPassword);
  }

  public void setFullName(String value) { fullName = value == null ? null : value.strip(); }
  public void setPhone(String value) { phone = value == null ? null : value.strip(); }
  public void setEmail(String value) { email = value == null ? null : value.strip().toLowerCase(Locale.ROOT); }
  public void setUsername(String value) { username = value == null ? null : value.strip().toLowerCase(Locale.ROOT); }
}
