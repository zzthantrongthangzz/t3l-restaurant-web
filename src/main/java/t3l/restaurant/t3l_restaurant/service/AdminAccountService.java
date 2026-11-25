package t3l.restaurant.t3l_restaurant.service;

import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.dto.AdminAccountForm;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminAccountService {
  private final UserAccountRepository accounts;
  private final PasswordEncoder passwords;
  private final Validator validator;
  private final AdminAudit audit;

  public record AccountView(Long id, String username, String fullName, String email, String phone, String role, boolean enabled) {
    public boolean isCustomer() { return "BUYER".equals(role); }
    public String getRoleLabel() { return isCustomer() ? "Khách hàng" : "ADMIN".equals(role) ? "Quản trị viên" : "Nhân viên"; }
  }

  @Transactional(readOnly = true)
  public Page<AccountView> search(String role, Boolean enabled, String text, int page) {
    String storedRole = "CUSTOMER".equals(role) ? "BUYER" : role;
    if (!List.of("", "BUYER", "STAFF", "ADMIN").contains(storedRole)) throw new IllegalArgumentException("Vai trò không hợp lệ.");
    return accounts.searchAdmin(storedRole, enabled, AdminInput.like(text),
        PageRequest.of(Math.max(page, 0), 20, Sort.by("username"))).map(this::view);
  }

  @Transactional(readOnly = true)
  public AccountView detail(Long id) { return view(accounts.findById(id).orElseThrow(() -> missing())); }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void save(Long id, AdminAccountForm form) {
    validator.validate(form).stream().findFirst().ifPresent(error -> {
      throw new IllegalArgumentException(error.getMessage());
    });
    if (!List.of("STAFF", "ADMIN").contains(form.getRole())) throw new IllegalArgumentException("Chỉ cấp vai trò STAFF hoặc ADMIN cho nhân viên.");
    var administrators = accounts.lockAdministrators();
    String actor = requireActiveAdministrator(administrators);
    UserAccount account;
    String password = form.getPassword();
    if (id == null) {
      String username = AdminInput.optional(form.getUsername());
      if (username == null || !username.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,79}")) {
        throw new IllegalArgumentException("Tên đăng nhập phải có 3–80 ký tự, gồm chữ, số, dấu chấm, gạch ngang hoặc gạch dưới.");
      }
      if (accounts.existsByUsernameIgnoreCase(username)) throw new IllegalArgumentException("Tên đăng nhập đã được dùng.");
      account = new UserAccount(username, encode(password), form.getRole());
      password = null;
    } else {
      account = accounts.lockById(id).orElseThrow(() -> missing());
      if ("BUYER".equals(account.getRole())) throw new IllegalArgumentException("Thông tin hồ sơ khách được quản lý trong tài khoản khách hàng.");
      protectAdministrator(account, form.getRole(), form.isEnabled(), actor, administrators);
    }
    String email = AdminInput.optional(form.getEmail());
    if (email != null && accounts.findByEmailIgnoreCase(email).filter(other -> !Objects.equals(other.getId(), id)).isPresent()) {
      throw new IllegalArgumentException("Email đã được dùng cho tài khoản khác.");
    }
    String oldRole = account.getRole();
    boolean oldEnabled = account.isEnabled();
    account.updateCustomerContact(form.getFullName().strip(), email, AdminInput.optional(form.getPhone()));
    account.manage(form.getRole(), form.isEnabled(), password == null || password.isBlank() ? null : encode(password));
    accounts.save(account);
    audit.record(id == null ? "Tạo tài khoản" : "Cập nhật tài khoản", account.getUsername() + " · "
        + oldRole + " → " + account.getRole() + (oldEnabled != account.isEnabled() ? (account.isEnabled() ? " · Mở khóa" : " · Khóa") : ""));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void setEnabled(Long id, boolean enabled) {
    var administrators = accounts.lockAdministrators();
    String actor = requireActiveAdministrator(administrators);
    var account = accounts.lockById(id).orElseThrow(() -> missing());
    protectAdministrator(account, account.getRole(), enabled, actor, administrators);
    if (account.isEnabled() == enabled) return;
    account.manage(account.getRole(), enabled, null);
    audit.record(enabled ? "Mở khóa tài khoản" : "Khóa tài khoản", account.getUsername());
  }

  private String requireActiveAdministrator(List<UserAccount> administrators) {
    String actor = SecurityContextHolder.getContext().getAuthentication().getName();
    if (administrators.stream().noneMatch(account -> account.isEnabled() && account.getUsername().equals(actor))) {
      throw new org.springframework.security.access.AccessDeniedException("Tài khoản quản trị không còn hiệu lực.");
    }
    return actor;
  }

  private void protectAdministrator(UserAccount account, String nextRole, boolean enabled, String actor,
      List<UserAccount> administrators) {
    if (!"ADMIN".equals(account.getRole()) || ("ADMIN".equals(nextRole) && enabled)) return;
    if (account.getUsername().equals(actor)) throw new IllegalArgumentException("Không thể tự khóa hoặc tự hạ quyền quản trị.");
    if (account.isEnabled() && administrators.stream().filter(UserAccount::isEnabled).count() <= 1) {
      throw new IllegalArgumentException("Hệ thống phải còn ít nhất một ADMIN đang hoạt động.");
    }
  }

  private String encode(String password) {
    if (password == null || password.isBlank() || password.length() < 12
        || password.getBytes(StandardCharsets.UTF_8).length > 72) {
      throw new IllegalArgumentException("Mật khẩu phải có ít nhất 12 ký tự và không vượt quá 72 byte.");
    }
    return passwords.encode(password);
  }

  private IllegalArgumentException missing() { return new IllegalArgumentException("Không tìm thấy tài khoản."); }

  private AccountView view(UserAccount account) {
    return new AccountView(account.getId(), account.getUsername(), account.getFullName(), account.getEmail(),
        account.getPhone(), account.getRole(), account.isEnabled());
  }
}
