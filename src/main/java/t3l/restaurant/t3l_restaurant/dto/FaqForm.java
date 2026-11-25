package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FaqForm {
  @NotBlank(message = "Vui lòng nhập câu hỏi.")
  private String question;

  @NotBlank(message = "Vui lòng nhập câu trả lời.")
  private String answer;

  @NotNull(message = "Vui lòng nhập thứ tự hiển thị.")
  private Integer displayOrder;

  private boolean published;
}
