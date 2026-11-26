package t3l.restaurant.t3l_restaurant.controller;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import t3l.restaurant.t3l_restaurant.dto.AdminAccountForm;
import t3l.restaurant.t3l_restaurant.dto.AdminTableForm;
import t3l.restaurant.t3l_restaurant.dto.RestaurantSettingsForm;
import t3l.restaurant.t3l_restaurant.entity.TableKind;
import t3l.restaurant.t3l_restaurant.service.AdminAccountService;
import t3l.restaurant.t3l_restaurant.service.AdminInventoryService;
import t3l.restaurant.t3l_restaurant.service.AdminSettingsService;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminManagementController {
  private final AdminAccountService accounts;
  private final AdminInventoryService inventory;
  private final AdminSettingsService settings;

  @GetMapping("/accounts")
  public String accounts(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String role,
      @RequestParam(required = false) Boolean enabled, @RequestParam(defaultValue = "0") int page,
      Authentication authentication, Model model) {
    if (!List.of("", "STAFF", "ADMIN", "CUSTOMER").contains(role)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    model.addAttribute("rows", accounts.search(role, enabled, q, page));
    model.addAttribute("q", q);
    model.addAttribute("role", role);
    model.addAttribute("enabled", enabled);
    model.addAttribute("actor", authentication.getName());
    return "admin/accounts";
  }

  @GetMapping("/accounts/new")
  public String newAccount(Model model) {
    model.addAttribute("form", new AdminAccountForm());
    return "admin/account-form";
  }

  @GetMapping("/accounts/{id}")
  public String editAccount(@PathVariable Long id, Model model) {
    var account = accounts.detail(id);
    var form = new AdminAccountForm();
    form.setUsername(account.username());
    form.setFullName(account.fullName());
    form.setEmail(account.email());
    form.setPhone(account.phone());
    form.setRole(account.role());
    form.setEnabled(account.enabled());
    model.addAttribute("account", account);
    model.addAttribute("form", form);
    return "admin/account-form";
  }

  @PostMapping({"/accounts", "/accounts/{id}"})
  public String saveAccount(@PathVariable(required = false) Long id, @ModelAttribute("form") AdminAccountForm form,
      org.springframework.validation.BindingResult errors, Model model, RedirectAttributes redirect) {
    if (!errors.hasErrors()) {
      try {
        accounts.save(id, form);
        redirect.addFlashAttribute("notice", "Đã lưu tài khoản.");
        return "redirect:" + accountList(id);
      } catch (IllegalArgumentException exception) {
        errors.reject("account.invalid", exception.getMessage());
      } catch (DataIntegrityViolationException exception) {
        errors.reject("account.duplicate", "Tên đăng nhập hoặc thông tin liên hệ đã được sử dụng.");
      } catch (TransientDataAccessException exception) {
        errors.reject("account.busy", "Tài khoản đang được cập nhật. Vui lòng thử lại.");
      }
    }
    if (id != null) model.addAttribute("account", accounts.detail(id));
    form.setPassword(null);
    return "admin/account-form";
  }

  @PostMapping("/accounts/{id}/availability")
  public String accountAvailability(@PathVariable Long id, @RequestParam boolean enabled,
      @RequestParam(required = false) String reason, RedirectAttributes redirect) {
    return apply(accountList(id), enabled ? "Đã mở khóa tài khoản." : "Đã khóa tài khoản.",
        () -> accounts.setEnabled(id, enabled, reason), redirect);
  }

  private String accountList(Long id) {
    return id != null && accounts.detail(id).isCustomer() ? "/admin/accounts?role=CUSTOMER" : "/admin/accounts";
  }

  @GetMapping("/inventory")
  public String inventory(@RequestParam(required = false) TableKind kind, Model model) {
    model.addAttribute("activeTableIds", inventory.activeTableIds());
    model.addAttribute("tables", inventory.tables().stream().filter(table -> kind == null || kind == table.getKind()).toList());
    model.addAttribute("selectedKind", kind);
    model.addAttribute("tableKinds", TableKind.values());
    return "admin/inventory";
  }

  @GetMapping("/inventory/new")
  public String newTable(Model model) {
    model.addAttribute("form", new AdminTableForm());
    return tablePage(null, model);
  }

  @GetMapping("/inventory/{id}/edit")
  public String editTable(@PathVariable Long id, Model model) {
    model.addAttribute("form", inventory.tableForm(id));
    return tablePage(id, model);
  }

  @PostMapping({"/inventory", "/inventory/{id}"})
  public String saveTable(@PathVariable(required = false) Long id, @ModelAttribute("form") AdminTableForm form,
      org.springframework.validation.BindingResult errors, Model model, RedirectAttributes redirect) {
    if (!errors.hasErrors()) {
      try {
        inventory.saveTable(id, form);
        redirect.addFlashAttribute("notice", "Đã lưu bàn.");
        return "redirect:/admin/inventory";
      } catch (IllegalArgumentException exception) {
        errors.reject("table.invalid", exception.getMessage());
      } catch (DataIntegrityViolationException exception) {
        errors.reject("table.duplicate", "Mã bàn đã được sử dụng.");
      } catch (TransientDataAccessException exception) {
        errors.reject("table.busy", "Sơ đồ bàn đang được cập nhật. Vui lòng thử lại.");
      }
    }
    return tablePage(id, model);
  }

  private String tablePage(Long id, Model model) {
    model.addAttribute("tableId", id);
    model.addAttribute("tableKinds", TableKind.values());
    return "admin/table-form";
  }

  @GetMapping("/categories")
  public String categories(Model model) {
    model.addAttribute("categories", inventory.sectionList("MENU"));
    return "admin/categories";
  }

  @PostMapping("/{section:areas|categories}")
  public String saveSection(@PathVariable String section, @RequestParam(required = false) Long id,
      @RequestParam(defaultValue = "") String previous, @RequestParam String name,
      @RequestParam(defaultValue = "false") boolean active, RedirectAttributes redirect) {
    String kind = "areas".equals(section) ? "AREA" : "MENU";
    return apply("AREA".equals(kind) ? "/admin/inventory" : "/admin/categories", "Đã lưu danh mục.",
        () -> inventory.saveSection(kind, id, previous, name, active), redirect);
  }

  @GetMapping("/settings")
  public String settings(Model model) {
    model.addAttribute("form", settings.form());
    return "admin/settings";
  }

  @PostMapping("/settings")
  public String saveSettings(@ModelAttribute("form") RestaurantSettingsForm form,
      org.springframework.validation.BindingResult errors, RedirectAttributes redirect) {
    if (errors.hasErrors()) return "admin/settings";
    try {
      settings.save(form);
      redirect.addFlashAttribute("notice", "Đã cập nhật cấu hình nhà hàng.");
      return "redirect:/admin/settings";
    } catch (IllegalArgumentException exception) {
      errors.reject("settings.invalid", exception.getMessage());
    } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
      errors.reject("settings.conflict", "Cấu hình vừa được cập nhật. Hãy tải lại trang trước khi lưu.");
    }
    return "admin/settings";
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public String notFound(IllegalArgumentException error, jakarta.servlet.http.HttpServletResponse response, Model model) {
    response.setStatus(HttpStatus.NOT_FOUND.value());
    model.addAttribute("message", error.getMessage());
    return "admin/invalid";
  }

  private String apply(String path, String notice, Runnable operation, RedirectAttributes redirect) {
    try {
      operation.run();
      redirect.addFlashAttribute("notice", notice);
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    } catch (DataIntegrityViolationException | TransientDataAccessException exception) {
      redirect.addFlashAttribute("error", "Dữ liệu vừa thay đổi hoặc đã được sử dụng. Hãy tải lại và thử lại.");
    }
    return "redirect:" + path;
  }
}
