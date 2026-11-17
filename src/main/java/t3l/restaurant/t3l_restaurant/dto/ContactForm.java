package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class ContactForm {
  @NotBlank(message = "Vui lòng nhập họ tên.")
  @Size(max = 120, message = "Họ tên không vượt quá 120 ký tự.")
  private String name;

  @NotBlank(message = "Vui lòng nhập email.")
  @Email(message = "Email không đúng định dạng.")
  @Size(max = 254, message = "Email không vượt quá 254 ký tự.")
  private String email;

  @NotBlank(message = "Vui lòng nhập số điện thoại.")
  @Size(max = 30, message = "Số điện thoại không vượt quá 30 ký tự.")
  @Pattern(regexp = "(?=(?:[^0-9]*[0-9]){8,15}[^0-9]*$)\\+?[0-9 () .-]+", message = "Số điện thoại cần có từ 8 đến 15 chữ số.")
  private String phone;

  @NotBlank(message = "Vui lòng nhập chủ đề.")
  @Size(max = 150, message = "Chủ đề không vượt quá 150 ký tự.")
  private String subject;

  @NotBlank(message = "Vui lòng nhập nội dung liên hệ.")
  @Size(min = 10, max = 4000, message = "Nội dung cần từ 10 đến 4.000 ký tự.")
  private String message;
}
