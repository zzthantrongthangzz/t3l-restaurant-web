package t3l.restaurant.t3l_restaurant.service;

import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
  private final ReservationSchedule schedule;

  public record AccountView(Long id, String username, String fullName, String email, String phone, String role,
                            boolean enabled, LocalDateTime archivedAt) {
    public boolean isCustomer() { return "BUYER".equals(role); }
    public boolean isArchived() { return archivedAt != null; }
    public String getStatusLabel() { return isArchived() ? "Đã lưu trữ" : enabled ? "Hoạt động" : "Đã khóa"; }
    public String getRoleLabel() { return isCustomer() ? "Khách hàng" : "ADMIN".equals(role) ? "Quản trị viên" : "Nhân viên"; }
  }

  @Transactional(readOnly = true)
  public Page<AccountView> search(String role, String state, String text, int page) {
    String storedRole = "CUSTOMER".equals(role) ? "BUYER" : role;
    if (!List.of("", "BUYER", "STAFF", "ADMIN").contains(storedRole)) throw new IllegalArgumentException("Vai trò không hợp lệ.");
    return accounts.searchAdmin(storedRole, state, AdminInput.like(text),
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
      requireNotArchived(account);
      if ("BUYER".equals(account.getRole())) throw new IllegalArgumentException("Thông tin hồ sơ khách được quản lý trong tài khoản khách hàng.");
      protectAdministrator(account, form.getRole(), account.isEnabled(), actor, administrators);
    }
    String email = AdminInput.optional(form.getEmail());
    if (email != null && accounts.findByEmailIgnoreCase(email).filter(other -> !Objects.equals(other.getId(), id)).isPresent()) {
      throw new IllegalArgumentException("Email đã được dùng cho tài khoản khác.");
    }
    String oldRole = account.getRole();
    account.updateCustomerContact(form.getFullName().strip(), email, AdminInput.optional(form.getPhone()));
    account.manage(form.getRole(), id == null ? form.isEnabled() : account.isEnabled(),
        password == null || password.isBlank() ? null : encode(password));
    accounts.saveAndFlush(account);
    String details = id == null ? "Đã tạo tài khoản với vai trò " + roleLabel(account.getRole()) + "."
        : "Đã cập nhật thông tin tài khoản.";
    if (id != null && !oldRole.equals(account.getRole())) {
      details += " Vai trò đã đổi từ " + roleLabel(oldRole) + " sang " + roleLabel(account.getRole()) + ".";
    }
    if (id != null && password != null && !password.isBlank()) details += " Đã đặt lại mật khẩu.";
    audit.record(id == null ? "Tạo tài khoản" : "Cập nhật tài khoản", "ACCOUNT", account.getId(), account.getUsername(),
        details);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void setEnabled(Long id, boolean enabled, String reason) {
    var administrators = accounts.lockAdministrators();
    String actor = requireActiveAdministrator(administrators);
    var account = accounts.lockById(id).orElseThrow(() -> missing());
    requireNotArchived(account);
    if (account.isEnabled() == enabled) return;
    protectAdministrator(account, account.getRole(), enabled, actor, administrators);
    String details = enabled ? "Đã mở khóa tài khoản. Người dùng có thể đăng nhập lại."
        : "Đã khóa tài khoản và kết thúc các phiên đăng nhập hiện tại.";
    audit.record(enabled ? "Mở khóa tài khoản" : "Khóa tài khoản", "ACCOUNT", id, account.getUsername(),
        AdminAudit.withReason(details, reason));
    account.manage(account.getRole(), enabled, null);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void setArchived(Long id, boolean archived, String reason) {
    var administrators = accounts.lockAdministrators();
    String actor = requireActiveAdministrator(administrators);
    var account = accounts.lockById(id).orElseThrow(() -> missing());
    if ((account.getArchivedAt() != null) == archived) return;
    if (reason == null || reason.isBlank() || reason.strip().length() > 250) {
      throw new IllegalArgumentException("Vui lòng nhập lý do từ 1 đến 250 ký tự.");
    }
    if (archived) {
      if (account.getUsername().equals(actor)) throw new IllegalArgumentException("Không thể tự lưu trữ tài khoản quản trị.");
      protectAdministrator(account, account.getRole(), false, actor, administrators);
      account.archive(LocalDateTime.now(schedule.getClock()));
    } else {
      account.restore();
    }
    audit.record(archived ? "Lưu trữ tài khoản" : "Khôi phục tài khoản", "ACCOUNT", id, account.getUsername(),
        AdminAudit.withReason(archived ? "Đã lưu trữ tài khoản, kết thúc phiên đăng nhập và giữ lại lịch sử."
            : "Đã khôi phục tài khoản với mã cũ. Tài khoản vẫn khóa cho đến khi quản trị viên mở khóa.", reason));
  }

  private void requireNotArchived(UserAccount account) {
    if (account.getArchivedAt() != null) {
      throw new IllegalArgumentException("Hãy khôi phục tài khoản đã lưu trữ trước khi cập nhật hoặc mở khóa.");
    }
  }

  private String roleLabel(String role) {
    return "ADMIN".equals(role) ? "quản trị viên" : "nhân viên";
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
        account.getPhone(), account.getRole(), account.isEnabled(), account.getArchivedAt());
  }
}
