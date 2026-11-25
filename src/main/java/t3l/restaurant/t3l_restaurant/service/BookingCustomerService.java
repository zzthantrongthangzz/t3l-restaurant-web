package t3l.restaurant.t3l_restaurant.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.CustomerProfileRepository;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;

@Service
@RequiredArgsConstructor
public class BookingCustomerService {
  private final UserAccountRepository accounts;
  private final CustomerProfileRepository profiles;
  private final CustomerAccountService customers;
  private final PasswordEncoder passwords;

  public record Contact(Long id, String fullName, String email, String phone) {
    public boolean complete() { return fullName != null && !fullName.isBlank(); }
  }

  public String normalizePhone(String input) {
    String phone = input == null || input.length() > 30 ? null : customers.bookingPhone(input);
    if (phone == null) throw new IllegalArgumentException("Vui lòng nhập số điện thoại hợp lệ.");
    return phone;
  }

  @Transactional(readOnly = true)
  public Contact find(String phone) {
    var account = findAccount(normalizePhone(phone));
    return account == null ? null : contact(account);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Contact save(String phone, String fullName, String email) {
    String normalized = normalizePhone(phone);
    String name = fullName == null ? "" : fullName.strip();
    String address = email == null || email.isBlank() ? null : email.strip();
    if (name.isBlank() || name.length() > 120) throw new IllegalArgumentException("Vui lòng nhập họ tên (tối đa 120 ký tự).");
    if (address != null && (address.length() > 254 || !address.matches("[^\\s@]+@[^\\s@]+"))) {
      throw new IllegalArgumentException("Địa chỉ email không hợp lệ.");
    }
    var account = findAccount(normalized);
    if (account != null) {
      account = accounts.lockById(account.getId()).orElseThrow();
      if (contact(account).complete()) return contact(account);
      if (account.getEmail() != null) address = account.getEmail();
    }
    long existingId = account == null ? 0L : account.getId();
    if (address != null && (accounts.existsByEmailIgnoreCaseAndIdNot(address, existingId)
        || profiles.existsByGoogleEmailIgnoreCaseAndIdNot(address, existingId))) {
      throw new IllegalArgumentException("Email này đã được dùng cho một tài khoản khác. Hãy kiểm tra thông tin khách.");
    }
    if (account == null) {
      account = UserAccount.customer(name, address, normalized, "desk:" + normalized.replace("+", ""),
          passwords.encode(UUID.randomUUID().toString()));
    } else account.updateCustomerContact(name, address, account.getPhone());
    try {
      return contact(accounts.saveAndFlush(account));
    } catch (DataIntegrityViolationException conflict) {
      throw new IllegalArgumentException("Thông tin vừa được lưu hoặc bị trùng. Hãy tra cứu lại bằng số điện thoại.", conflict);
    }
  }

  public UserAccount requireAccount(Long id) {
    if (id == null) throw new BookingException(null, "Vui lòng tra cứu và chọn hồ sơ khách hàng.");
    return accounts.findById(id).filter(account -> "BUYER".equals(account.getRole()) && contact(account).complete()
            && customers.bookingPhone(account.getPhone()) != null)
        .orElseThrow(() -> new BookingException(null, "Vui lòng tra cứu và chọn hồ sơ khách hàng."));
  }

  private UserAccount findAccount(String phone) {
    String digits = phone.replace("+", "");
    String alternate = SmsOtpService.normalizePhone(phone) == null ? digits : "84" + digits.substring(1);
    var matches = accounts.findBuyersByContactPhone(digits, alternate);
    if (matches.size() > 1) throw new IllegalArgumentException("Có nhiều hồ sơ dùng số này. Hãy nhờ quản trị viên kiểm tra.");
    return matches.isEmpty() ? null : matches.getFirst();
  }

  private Contact contact(UserAccount account) {
    return new Contact(account.getId(), account.getFullName(), account.getEmail(), account.getPhone());
  }
}
