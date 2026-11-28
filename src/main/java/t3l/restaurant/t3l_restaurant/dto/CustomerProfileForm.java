package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CustomerProfileForm {
  @NotNull
  @Size(max = 120, message = "Họ không vượt quá 120 ký tự.")
  private String familyName = "";

  @NotBlank(message = "Vui lòng nhập tên.")
  @Size(max = 120, message = "Tên không vượt quá 120 ký tự.")
  private String givenName = "";

  @Size(max = 30, message = "Số điện thoại không vượt quá 30 ký tự.")
  @Pattern(regexp = "(?:|(?=(?:[^0-9]*[0-9]){8,15}[^0-9]*$)\\+?[0-9 () .-]+)",
      message = "Số điện thoại cần có từ 8 đến 15 chữ số.")
  private String phone;

  @Size(max = 500, message = "Chế độ ăn đặc biệt không vượt quá 500 ký tự.")
  private String dietaryPreferences;

  private boolean bookingNotifications;
  private boolean marketingOptIn;

  @AssertTrue(message = "Họ và tên không vượt quá 120 ký tự.")
  public boolean isFullNameWithinLimit() {
    return familyName == null || givenName == null || (familyName.strip() + " " + givenName.strip()).strip().length() <= 120;
  }

  public void setPhone(String phone) {
    this.phone = phone == null ? null : phone.strip();
  }
}
