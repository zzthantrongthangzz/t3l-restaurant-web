package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import t3l.restaurant.t3l_restaurant.entity.TableKind;

@Getter @Setter
public class AdminTableForm {
  @NotBlank @Size(max = 32) private String code;
  @Size(max = 40) private String area;
  @Size(max = 40) private String kind;
  private TableKind classification = TableKind.SMALL;
  @NotNull @Min(1) @Max(100) private Integer capacity = 4;
  private boolean active = true;
}
