package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

@Getter
@Setter
public class ReservationForm {
  @NotBlank(message = "Vui lòng nhập họ tên.")
  @Size(max = 120, message = "Họ tên không vượt quá 120 ký tự.")
  private String guestName;

  @NotBlank(message = "Vui lòng nhập số điện thoại.")
  @Size(max = 30, message = "Số điện thoại không hợp lệ.")
  @Pattern(regexp = "(?=(?:\\D*\\d){8,15}\\D*$)\\+?[0-9][0-9() .-]*[0-9]",
      message = "Số điện thoại phải có từ 8 đến 15 chữ số.")
  private String guestPhone;

  @Email(message = "Địa chỉ email không hợp lệ.")
  @Size(max = 254, message = "Email không vượt quá 254 ký tự.")
  private String guestEmail;

  @Size(max = 40, message = "Khu vực ưu tiên không hợp lệ.")
  private String preferredArea;

  private Long tableId;

  @Size(max = 30, message = "Chỉ có thể chọn tối đa 30 món đặt trước.")
  private List<Long> preorderDishIds = new ArrayList<>();

  @NotNull(message = "Vui lòng chọn ngày đến.")
  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  private LocalDate date;
  @NotNull(message = "Vui lòng chọn giờ đến.")
  @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
  private LocalTime time;

  @NotNull(message = "Vui lòng chọn số khách.")
  @Min(value = 1, message = "Số khách phải từ 1 người.")
  @Max(value = 20, message = "Mỗi yêu cầu nhận tối đa 20 khách.")
  private Integer guests;

  @Size(max = 500, message = "Ghi chú không vượt quá 500 ký tự.")
  private String note;

  @AssertTrue(message = "Vui lòng đồng ý với chính sách đặt bàn và quyền riêng tư.")
  private boolean consentAccepted;
}
