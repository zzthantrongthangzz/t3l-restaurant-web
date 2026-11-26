package t3l.restaurant.t3l_restaurant.dto;

import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

@Getter @Setter
public class AdminReportFilter {
  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate from;
  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate to;
  private String employee = "";
  private String method = "";
  private String status = "";
  private String code = "";
  private String action = "";
  private int page;
}
