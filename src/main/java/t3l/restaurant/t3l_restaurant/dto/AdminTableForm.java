package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class AdminTableForm {
  @NotBlank @Size(max = 32) private String code;
  @NotBlank @Size(max = 40) private String area;
  @Size(max = 40) private String kind;
  @NotNull @Min(1) @Max(100) private Integer capacity = 4;
  private boolean active = true;
}
