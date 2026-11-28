package t3l.restaurant.t3l_restaurant.service;

import java.util.UUID;
import java.util.Optional;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.CustomerProfileRepository;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;
import t3l.restaurant.t3l_restaurant.service.SmsOtpService.OtpException;

@Service
public class PhoneCustomerService {
  private static final String LOCAL_PREFIX = "local-sms:";
  private final UserAccountRepository accounts;
  private final CustomerProfileRepository profiles;
  private final PasswordEncoder passwords;

  public PhoneCustomerService(UserAccountRepository accounts, CustomerProfileRepository profiles,
      PasswordEncoder passwords) {
    this.accounts = accounts;
    this.profiles = profiles;
    this.passwords = passwords;
  }

  public boolean canRecover(String phone) {
    return accounts.findByVerifiedPhone(phone).isEmpty()
        && accounts.countLegacyPhoneMatches(phone, "84" + phone.substring(1)) > 0;
  }

  public boolean canRegister(String phone) {
    return phone != null && findPhoneMatches(phone).isEmpty();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public UserAccount registerPassword(String fullName, String inputPhone, String email, String encodedPassword,
      Instant verifiedAt) {
    String name = fullName == null ? "" : fullName.strip().replaceAll("\\s+", " ");
    String phone = SmsOtpService.normalizePhone(inputPhone);
    if (name.isBlank() || name.length() > 120 || phone == null
        || encodedPassword == null || encodedPassword.isBlank() || encodedPassword.length() > 100) {
      throw new IllegalArgumentException("Vui lòng kiểm tra lại thông tin đăng ký.");
    }
    if (!canRegister(phone)) {
      throw new IllegalArgumentException("Số điện thoại đã có tài khoản hoặc hồ sơ khách. Vui lòng đăng nhập hoặc liên hệ nhà hàng.");
    }
    var account = UserAccount.customer(name, email, phone, "phone:" + phone, encodedPassword);
    account.verifyEmail(email, verifiedAt);
    return accounts.saveAndFlush(account);
  }

  public java.util.List<UserAccount> findPhoneMatches(String phone) {
    String digits = phone.replace("+", "");
    String alternate = SmsOtpService.normalizePhone(phone) == null ? digits : "84" + digits.substring(1);
    return accounts.findBuyersByContactPhone(digits, alternate);
  }

  public Optional<UserAccount> findPasswordAccount(String phone) {
    var phoneMatches = findPhoneMatches(phone);
    return accounts.findByVerifiedPhone(phone)
        .or(() -> accounts.findByUsername(LOCAL_PREFIX + phone))
        .or(() -> phoneMatches.size() == 1 ? Optional.of(phoneMatches.getFirst()) : Optional.empty())
        .filter(account -> "BUYER".equals(account.getRole()));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public UserAccount register(
          String fullName,
          String inputPhone,
          boolean local,
          String encodedPassword
  ) {
    String name = fullName == null ? "" : fullName.strip().replaceAll("\\s+", " ");
    String phone = SmsOtpService.normalizePhone(inputPhone);

    if (name.isBlank() || name.length() > 120 || phone == null
            || encodedPassword == null || encodedPassword.isBlank()
            || encodedPassword.length() > 100) {
      throw new OtpException("Vui lòng kiểm tra lại thông tin đăng ký.");
    }

    if (local) {
      String localUsername = LOCAL_PREFIX + phone;
      var existing = accounts.findByUsername(localUsername);

      if (existing.isPresent()) {
        UserAccount account = requireCustomer(existing.get());
        account.updateCustomerContact(name, account.getEmail(), phone);
        account.manage(account.getRole(), account.isEnabled(), encodedPassword);
        return accounts.saveAndFlush(account);
      }
    } else if (!canRegister(phone)) {
      throw new OtpException(
              "Số điện thoại đã có tài khoản. Vui lòng đăng nhập hoặc khôi phục tài khoản.");
    }

    String username = (local ? LOCAL_PREFIX + phone : "sms:" + UUID.randomUUID());
    UserAccount account = UserAccount.customer(name, null, phone, username, encodedPassword);

    if (!local) {
      account.verifyPhone(phone);
    }

    return accounts.saveAndFlush(account);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public UserAccount signIn(String phone, boolean local) {
    String username = (local ? LOCAL_PREFIX : "sms:") + UUID.randomUUID();
    if (local) username = LOCAL_PREFIX + phone;
    var existing = local ? accounts.findByUsername(username) : accounts.findByVerifiedPhone(phone);
    if (existing.isPresent()) return requireCustomer(existing.get());
    if (findPhoneMatches(phone).stream().anyMatch(account -> !account.isEnabled())) {
      throw new OtpException("Tài khoản hiện không khả dụng. Vui lòng liên hệ nhà hàng.");
    }
    var account = UserAccount.customer(null, null, phone, username, passwords.encode(UUID.randomUUID().toString()));
    if (!local) account.verifyPhone(phone);
    return accounts.saveAndFlush(account);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public UserAccount recover(String phone, String username, String password) {
    if (username == null || username.length() > 80 || password == null || password.isEmpty()
        || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) throw recoveryError();
    var candidate = accounts.findByUsernameIgnoreCase(username.strip()).orElseThrow(this::recoveryError);
    var account = accounts.lockById(candidate.getId()).orElseThrow(this::recoveryError);
    if (!CustomerAccountService.isCustomer(account) || account.getVerifiedPhone() != null || isLocal(account)
        || !phone.equals(SmsOtpService.normalizePhone(account.getPhone()))
        || profiles.findById(account.getId()).map(profile -> profile.getGoogleSubject() != null).orElse(false)
        || !passwords.matches(password, account.getPassword())) {
      throw recoveryError();
    }
    requireUnclaimedPhone(phone, account);
    account.verifyPhone(phone);
    return accounts.saveAndFlush(account);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void link(Long accountId, String phone) {
    var account = accounts.lockById(accountId).map(this::requireCustomer).orElseThrow(this::recoveryError);
    if (isLocal(account) || (account.getVerifiedPhone() == null
        && profiles.findById(accountId).map(profile -> profile.getGoogleSubject() == null).orElse(true))) {
      throw new OtpException("Hãy xác thực tài khoản hiện tại trước khi liên kết số điện thoại.");
    }
    requireUnclaimedPhone(phone, account);
    account.verifyPhone(phone);
    accounts.saveAndFlush(account);
  }

  private void requireUnclaimedPhone(String phone, UserAccount account) {
    if (accounts.findByVerifiedPhone(phone).filter(existing -> !existing.getId().equals(account.getId())).isPresent()) {
      throw new OtpException("Số điện thoại này đã đăng nhập được vào một tài khoản khác.");
    }
  }

  public static boolean isLocal(UserAccount account) {
    return account.getUsername().startsWith(LOCAL_PREFIX);
  }

  private UserAccount requireCustomer(UserAccount account) {
    if (!CustomerAccountService.isCustomer(account)) throw new OtpException("Tài khoản hiện không khả dụng.");
    return account;
  }

  private OtpException recoveryError() {
    return new OtpException("Thông tin tài khoản cũ không khớp với số điện thoại đã xác thực.");
  }
}
