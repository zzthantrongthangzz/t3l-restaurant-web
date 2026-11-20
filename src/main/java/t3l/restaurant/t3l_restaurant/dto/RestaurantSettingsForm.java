package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.*;
import java.time.LocalTime;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

@Getter
@Setter
public class RestaurantSettingsForm {
  @NotBlank @Size(max = 300)
  private String address;
  @Size(max = 30) @Pattern(regexp = "(?:\\+?[0-9][0-9 .()/-]{5,27}[0-9])?")
  private String phone;
  @Email @Size(max = 254)
  private String email;
  @Size(max = 2000)
  private String aboutText;
  @Size(max = 500)
  private String aboutImageUrl;
  @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
  private LocalTime openingTime;
  @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
  private LocalTime closingTime;
  @NotNull @Min(15) @Max(720)
  private Integer durationMinutes;
  @NotNull @Min(5) @Max(120)
  private Integer slotMinutes;
  @NotNull @Min(0) @Max(10080)
  private Integer changeNoticeMinutes;
  @NotNull @Min(0) @Max(120)
  private Integer checkInMinutes;
  @NotNull @Min(0) @Max(120)
  private Integer noShowMinutes;
}
