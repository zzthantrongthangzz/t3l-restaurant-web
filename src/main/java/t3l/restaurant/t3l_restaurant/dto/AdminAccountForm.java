package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class AdminAccountForm {
  @Size(max = 80) private String username;
  @NotBlank @Size(max = 120) private String fullName;
  @Email @Size(max = 254) private String email;
  @Size(max = 30) @Pattern(regexp = "(?:\\+?[0-9][0-9 .()/-]{5,27}[0-9])?") private String phone;
  @Size(max = 72) private String password;
  @NotBlank private String role = "STAFF";
  private boolean enabled = true;
}
