package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReservationCancellationForm {
  @Size(max = 500, message = "Lý do không vượt quá 500 ký tự.")
  private String reason;
}
