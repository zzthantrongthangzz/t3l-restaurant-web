package t3l.restaurant.t3l_restaurant.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.dto.VoucherForm;
import t3l.restaurant.t3l_restaurant.service.BookingException;
import t3l.restaurant.t3l_restaurant.service.VoucherService;

@Controller
@RequestMapping("/admin/vouchers")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminVoucherController {
  private final VoucherService vouchers;

  @GetMapping
  public String list(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,Model model) {
    model.addAttribute("rows",vouchers.list(q,status));
    model.addAttribute("q",q);
    model.addAttribute("status",status);
    return "admin/vouchers";
  }

  @GetMapping({"/new","/{id}"})
  public String edit(@PathVariable(required=false) Long id,@RequestParam(defaultValue="0") int page,Model model) {
    model.addAttribute("form",id==null ? new VoucherForm() : vouchers.get(id).form());
    return editor(id,page,model);
  }

  @PostMapping({"","/{id}"})
  public String save(@PathVariable(required=false) Long id,@Valid @ModelAttribute("form") VoucherForm form,
      BindingResult errors,Model model,RedirectAttributes redirect) {
    if (!errors.hasErrors()) {
      try {
        vouchers.save(id,form);
        redirect.addFlashAttribute("notice","Đã lưu voucher.");
        return "redirect:/admin/vouchers";
      } catch (BookingException error) {
        errors.reject("voucher.invalid",error.getMessage());
      } catch (DataIntegrityViolationException error) {
        errors.reject("voucher.conflict","Mã bị trùng hoặc dữ liệu voucher vừa thay đổi. Hãy kiểm tra lại.");
      }
    }
    return editor(id,0,model);
  }

  @PostMapping("/{id}/status")
  public String status(@PathVariable Long id,@RequestParam boolean enabled,RedirectAttributes redirect) {
    vouchers.setEnabled(id,enabled);
    redirect.addFlashAttribute("notice",enabled ? "Đã kích hoạt voucher." : "Đã vô hiệu hóa voucher.");
    return "redirect:/admin/vouchers";
  }

  private String editor(Long id,int page,Model model) {
    int index=Math.max(0,page);
    var history=id==null ? java.util.List.<t3l.restaurant.t3l_restaurant.entity.VoucherUsage>of() : vouchers.history(id,index);
    model.addAttribute("voucherId",id);
    model.addAttribute("history",history.stream().limit(25).toList());
    model.addAttribute("historyPage",index);
    model.addAttribute("historyNext",history.size()>25);
    return "admin/voucher-form";
  }

  @ExceptionHandler(BookingException.class)
  public String missing(BookingException error,jakarta.servlet.http.HttpServletResponse response,Model model) {
    response.setStatus(404);
    model.addAttribute("message",error.getMessage());
    return "admin/invalid";
  }
}
