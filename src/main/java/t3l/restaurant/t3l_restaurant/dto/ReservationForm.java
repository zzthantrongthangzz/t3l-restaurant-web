package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReservationForm {
  @NotBlank
  @Size(max = 120)
  private String guestName;

  @NotBlank
  @Pattern(regexp = "[+0-9() .-]{8,30}")
  private String guestPhone;

  @Email
  @Size(max = 180)
  private String guestEmail;

  @NotNull(message = "Vui lòng chọn bàn còn trống.")
  @Positive
  private Long tableId;

  @NotNull @FutureOrPresent private LocalDate date;
  @NotNull private LocalTime time;

  @NotNull
  @Min(1)
  @Max(20)
  private Integer guests;

  @Size(max = 500)
  private String note;

  @AssertTrue(message = "Vui lòng đồng ý với chính sách đặt bàn và quyền riêng tư.")
  private boolean consentAccepted;
}
