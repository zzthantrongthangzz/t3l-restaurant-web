package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import t3l.restaurant.t3l_restaurant.entity.TableKind;

@Getter
@Setter
public class ReservationUpdateForm {
  @NotNull(message = "Vui lòng chọn ngày mới.")
  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  private LocalDate date;
  @NotNull(message = "Vui lòng chọn giờ mới.")
  @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
  private LocalTime time;
  @NotNull(message = "Vui lòng nhập số khách.")
  @Min(value = 1, message = "Số khách phải từ 1 người.")
  @Max(value = TableKind.MAX_CAPACITY, message = "Mỗi yêu cầu nhận tối đa {value} khách.")
  private Integer guests;
  @Size(max = 40, message = "Khu vực ưu tiên không hợp lệ.")
  private String preferredArea;
  @Size(max = 500, message = "Lý do không vượt quá 500 ký tự.")
  private String reason;

  public ReservationForm scheduleForm() {
    ReservationForm form = new ReservationForm();
    form.setDate(date);
    form.setTime(time);
    form.setGuests(guests);
    form.setPreferredArea(preferredArea);
    return form;
  }
}
