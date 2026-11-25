package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

@Getter @Setter
public class VoucherForm {
  @NotBlank @Size(max=32) private String code;
  @NotBlank @Size(max=120) private String title;
  @NotBlank private String discountType = "PERCENT";
  @NotNull @Positive @Digits(integer=16, fraction=2) private BigDecimal discountValue;
  @NotNull @PositiveOrZero @Digits(integer=16, fraction=0) private BigDecimal minimumTotal = BigDecimal.ZERO;
  @Positive @Digits(integer=16, fraction=0) private BigDecimal maximumDiscount;
  @NotNull @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) private LocalDateTime startsAt;
  @NotNull @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) private LocalDateTime endsAt;
  @Positive private Integer totalLimit;
  @Positive private Integer customerLimit;
  private boolean publicOffer = true;
  private boolean enabled = true;
}
