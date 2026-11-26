package t3l.restaurant.t3l_restaurant.service;

import java.io.Serializable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.entity.CustomerProfile;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.CustomerProfileRepository;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;
import t3l.restaurant.t3l_restaurant.service.SmsOtpService.OtpException;

@Service
public class GoogleCustomerService {
  private final UserAccountRepository accounts;
  private final CustomerProfileRepository profiles;
  private final PasswordEncoder passwords;

  public GoogleCustomerService(UserAccountRepository accounts, CustomerProfileRepository profiles,
      PasswordEncoder passwords) {
    this.accounts = accounts;
    this.profiles = profiles;
    this.passwords = passwords;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public String signIn(OidcUser user) {
    GoogleProfile google = GoogleProfile.from(user);
    Long linkedId = profiles.findAccountIdByGoogleSubject(google.subject()).orElse(null);
    if (linkedId != null) {
      UserAccount account = lockCustomer(linkedId);
      CustomerProfile profile = profiles.findById(linkedId).orElseThrow();
      if (!google.subject().equals(profile.getGoogleSubject())) {
        throw new OAuth2AuthenticationException("google_in_use");
      }
      completeMissingContact(account, profile, google);
      update(profile, google);
      return account.getUsername();
    }

    if (accounts.existsByEmailIgnoreCase(google.email()) || profiles.existsByGoogleEmailIgnoreCase(google.email())) {
      throw new OAuth2AuthenticationException("email_in_use");
    }
    String username = "google:" + UUID.randomUUID();
    UserAccount account = accounts.save(UserAccount.customer(google.name(), google.email(), null, username,
        passwords.encode(UUID.randomUUID().toString())));
    var profile = new CustomerProfile(account);
    profile.fillGoogleNames(google.family(), google.given());
    update(profile, google);
    return username;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public String link(Long accountId, OidcUser user) {
    GoogleProfile google = GoogleProfile.from(user);
    UserAccount account = lockCustomer(accountId);
    var profile = profiles.findById(accountId).orElseGet(() -> new CustomerProfile(account));
    Long linkedId = profiles.findAccountIdByGoogleSubject(google.subject()).orElse(null);
    if ((linkedId != null && !linkedId.equals(accountId))
        || (profile.getGoogleSubject() != null && !profile.getGoogleSubject().equals(google.subject()))
        || profiles.existsByGoogleEmailIgnoreCaseAndIdNot(google.email(), accountId)
        || accounts.existsByEmailIgnoreCaseAndIdNot(google.email(), accountId)) {
      throw new OAuth2AuthenticationException("google_in_use");
    }
    completeMissingContact(account, profile, google);
    update(profile, google);
    return account.getUsername();
  }

  public boolean canRecover(GoogleProfile google) {
    return accounts.findByEmailIgnoreCase(google.email()).filter(this::isLegacy).isPresent();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public UserAccount recover(GoogleProfile google, String username, String password) {
    if (username == null || username.length() > 80 || password == null || password.isEmpty()
        || password.getBytes(StandardCharsets.UTF_8).length > 72) throw recoveryError();
    var candidate = accounts.findByUsernameIgnoreCase(username.strip()).orElseThrow(this::recoveryError);
    var account = accounts.lockById(candidate.getId()).orElseThrow(this::recoveryError);
    if (!isLegacy(account) || !google.email().equalsIgnoreCase(account.getEmail())
        || profiles.findAccountIdByGoogleSubject(google.subject()).isPresent()
        || profiles.existsByGoogleEmailIgnoreCaseAndIdNot(google.email(), account.getId())
        || !passwords.matches(password, account.getPassword())) throw recoveryError();
    var profile = profiles.findById(account.getId()).orElseGet(() -> new CustomerProfile(account));
    completeMissingContact(account, profile, google);
    update(profile, google);
    return account;
  }

  private boolean isLegacy(UserAccount account) {
    return CustomerAccountService.isCustomer(account) && account.getVerifiedPhone() == null && !PhoneCustomerService.isLocal(account)
        && profiles.findById(account.getId()).map(profile -> profile.getGoogleSubject() == null).orElse(true);
  }

  private OtpException recoveryError() {
    return new OtpException("Thông tin tài khoản cũ không khớp với email Google đã xác thực.");
  }

  private UserAccount lockCustomer(Long id) {
    return accounts.lockById(id).filter(CustomerAccountService::isCustomer)
        .orElseThrow(() -> new OAuth2AuthenticationException("customer_unavailable"));
  }

  private void completeMissingContact(UserAccount account, CustomerProfile profile, GoogleProfile google) {
    String name = account.getFullName();
    if (name == null || name.isBlank()) name = google.name();
    String email = account.getEmail();
    if ((email == null || email.isBlank())
        && !accounts.existsByEmailIgnoreCaseAndIdNot(google.email(), account.getId())
        && !profiles.existsByGoogleEmailIgnoreCaseAndIdNot(google.email(), account.getId())) {
      email = google.email();
    }
    account.updateCustomerContact(name, email, account.getPhone());
    if (name.equals(google.name())) profile.fillGoogleNames(google.family(), google.given());
  }

  private void update(CustomerProfile profile, GoogleProfile google) {
    profile.updateGoogle(google.subject(), google.name(), google.email(), google.picture());
    profiles.saveAndFlush(profile);
  }

  public record GoogleProfile(String subject, String name, String email, String picture, String family, String given)
      implements Serializable {
    public static GoogleProfile from(OidcUser user) {
      var token = user.getIdToken();
      String subject = token.getSubject();
      String email = token.getEmail();
      if (subject == null || !subject.matches("[A-Za-z0-9_-]{1,255}")
          || !Boolean.TRUE.equals(token.getEmailVerified()) || email == null || email.length() > 254
          || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
        throw new OAuth2AuthenticationException("google_unverified");
      }
      email = email.toLowerCase(Locale.ROOT);
      String name = user.getFullName();
      if (name == null || name.isBlank()) name = email.substring(0, email.indexOf('@'));
      name = name.strip();
      if (name.length() > 120) name = name.substring(0, Character.isHighSurrogate(name.charAt(119)) ? 119 : 120);
      String family = user.getFamilyName();
      String given = user.getGivenName();
      if (given == null || ((family == null ? "" : family) + " " + given).strip().length() > 120) {
        family = null;
        given = null;
      }
      return new GoogleProfile(subject, name, email, pictureUrl(user.getPicture()), family, given);
    }

    private static String pictureUrl(String picture) {
      if (picture == null || picture.length() > 2048) return null;
      try {
        URI uri = URI.create(picture);
        String host = uri.getHost();
        if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
            && uri.getPort() == -1 && host != null
            && (host.equals("googleusercontent.com") || host.endsWith(".googleusercontent.com"))) {
          return picture;
        }
      } catch (IllegalArgumentException ignored) {
        return null;
      }
      return null;
    }
  }
}
