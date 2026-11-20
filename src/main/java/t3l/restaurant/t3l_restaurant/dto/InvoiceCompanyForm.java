package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InvoiceCompanyForm {
  @NotBlank(message = "Vui lòng nhập tên công ty.")
  @Size(max = 180, message = "Tên công ty không vượt quá 180 ký tự.")
  private String companyName;

  @NotBlank(message = "Vui lòng nhập mã số thuế.")
  @Pattern(regexp = "[0-9]{10}(?:-?[0-9]{3})?", message = "Vui lòng kiểm tra định dạng mã số thuế.")
  private String taxCode;

  @NotBlank(message = "Vui lòng nhập địa chỉ công ty.")
  @Size(max = 300, message = "Địa chỉ công ty không vượt quá 300 ký tự.")
  private String companyAddress;

  @NotBlank(message = "Vui lòng nhập email nhận hóa đơn.")
  @Email(message = "Email không đúng định dạng.")
  @Size(max = 254, message = "Email không vượt quá 254 ký tự.")
  private String invoiceEmail;
}
