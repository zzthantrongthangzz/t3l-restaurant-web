package t3l.restaurant.t3l_restaurant.service;

import java.util.UUID;
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

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public UserAccount signIn(String phone, boolean local) {
    String username = (local ? LOCAL_PREFIX : "sms:") + UUID.randomUUID();
    if (local) username = LOCAL_PREFIX + phone;
    var existing = local ? accounts.findByUsername(username) : accounts.findByVerifiedPhone(phone);
    if (existing.isPresent()) return requireCustomer(existing.get());
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
