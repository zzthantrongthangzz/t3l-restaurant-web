package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import org.springframework.web.multipart.MultipartFile;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DishForm {
  @NotBlank
  @Size(max = 140)
  private String name;

  @NotBlank
  @Size(max = 1000)
  private String description;

  @NotNull @Positive @Digits(integer = 12, fraction = 0) private BigDecimal price;

  private MultipartFile image;
  @Size(max = 500) private String imageUrl;

  @Size(max = 80)
  private String dietaryLabel;

  @NotBlank
  @Size(max = 80)
  private String categoryName;

  private boolean available = true;
  private boolean published = true;
}
