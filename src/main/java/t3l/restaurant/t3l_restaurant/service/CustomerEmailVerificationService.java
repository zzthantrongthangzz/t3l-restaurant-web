package t3l.restaurant.t3l_restaurant.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.WebUtils;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.CustomerProfileRepository;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;
import t3l.restaurant.t3l_restaurant.service.EmailOtpService.Challenge;
import t3l.restaurant.t3l_restaurant.service.EmailOtpService.VerificationException;

@Service
@RequiredArgsConstructor
public class CustomerEmailVerificationService {
  private static final String REGISTRATION = CustomerEmailVerificationService.class.getName() + ".registration";
  private static final String PROFILE = CustomerEmailVerificationService.class.getName() + ".profile";
  private static final Duration REAUTH_LIFETIME = Duration.ofMinutes(10);
  private final EmailOtpService otp;
  private final SmsOtpRateLimiter limiter;
  private final PhoneCustomerService phones;
  private final UserAccountRepository accounts;
  private final CustomerProfileRepository profiles;
  private final PasswordEncoder passwords;
  private final TransactionTemplate transactions;

  public static String normalizeEmail(String input) {
    return input == null ? null : ReservationEmailQueue.mailbox(input.strip().toLowerCase(Locale.ROOT));
  }

  public boolean emailInUse(String email, Long owner) {
    long id = owner == null ? 0 : owner;
    return accounts.existsByEmailIgnoreCaseAndIdNot(email, id) || profiles.existsByGoogleEmailIgnoreCaseAndIdNot(email, id);
  }

  public void startRegistration(HttpServletRequest request, String name, String phone, String email, String password,
      String target) {
    var session = request.getSession();
    synchronized (WebUtils.getSessionMutex(session)) {
      var previous = registration(session);
      var now = Instant.now();
      requireAvailable(email, null);
      reserve(request, email, now);
      var encoded = passwords.encode(password);
      var challenge = sendReserved(email, "xác minh đăng ký", previous == null ? null : previous.challenge(), Instant.now());
      session.setAttribute(REGISTRATION, new Registration(name, phone, email, encoded, target, challenge));
    }
  }

  public void resendRegistration(HttpServletRequest request) {
    var session = requireSession(request);
    synchronized (WebUtils.getSessionMutex(session)) {
      var pending = registration(session);
      if (pending == null) throw EmailOtpService.expired();
      requireAvailable(pending.email(), null);
      var challenge = send(request, pending.email(), "xác minh đăng ký", pending.challenge(), Instant.now());
      session.setAttribute(REGISTRATION, new Registration(pending.name(), pending.phone(), pending.email(),
          pending.encodedPassword(), pending.target(), challenge));
    }
  }

  public Registered verifyRegistration(HttpServletRequest request, String code) {
    var session = requireSession(request);
    synchronized (WebUtils.getSessionMutex(session)) {
      var pending = registration(session);
      if (pending == null) throw EmailOtpService.expired();
      var now = Instant.now();
      otp.verify(pending.challenge(), code.strip(), true, now);
      session.removeAttribute(REGISTRATION);
      var account = transactions.execute(status -> {
        requireAvailable(pending.email(), null);
        return phones.registerPassword(pending.name(), pending.phone(), pending.email(), pending.encodedPassword(), now);
      });
      return new Registered(account, pending.target());
    }
  }

  public String registrationEmail(HttpSession session) {
    var pending = registration(session);
    return pending == null ? null : EmailOtpService.mask(pending.email());
  }

  public String registrationTarget(HttpSession session) {
    var pending = registration(session);
    return pending == null ? null : pending.target();
  }

  public String cancelRegistration(HttpSession session) {
    if (session == null) return null;
    synchronized (WebUtils.getSessionMutex(session)) {
      var pending = registration(session);
      if (pending == null) return null;
      pending.challenge().cancel();
      session.removeAttribute(REGISTRATION);
      return pending.target();
    }
  }

  public void startProfile(HttpServletRequest request, UserAccount account, String input, String password) {
    String email = normalizeEmail(input);
    if (email == null) throw new VerificationException("Vui lòng nhập email hợp lệ.");
    var session = request.getSession();
    synchronized (WebUtils.getSessionMutex(session)) {
      var now = Instant.now();
      String reauthEmail = null;
      if (usesGoogleReauthentication(account)) {
        reauthEmail = googleEmail(account.getId());
        if (reauthEmail == null) throw new VerificationException("Hãy đăng nhập lại bằng tài khoản Google đã liên kết.");
        if (reauthEmail.equals(email)) {
          throw new VerificationException("Email này đã được Google xác minh. Nhập địa chỉ khác nếu bạn muốn đổi email.");
        }
      } else {
        try {
          limiter.reserveEmailReauthentication(account.getId(), request.getRemoteAddr(), now);
        } catch (SmsOtpRateLimiter.LimitReached exception) {
          throw rateLimited();
        }
        if (password.isEmpty() || password.length() > 72 || password.getBytes(StandardCharsets.UTF_8).length > 72
            || !passwords.matches(password, account.getPassword())) {
          throw new VerificationException("Mật khẩu hiện tại không đúng.");
        }
      }
      requireAvailable(email, account.getId());
      if (email.equals(account.getEmail()) && account.getEmailVerifiedAt() != null) {
        throw new VerificationException("Email này đã được xác minh.");
      }
      var previous = profile(session);
      var phase = reauthEmail == null ? ProfileStep.NEW_EMAIL : ProfileStep.GOOGLE_EMAIL;
      var challenge = send(request, reauthEmail == null ? email : reauthEmail, phase.purpose,
          previous == null ? null : previous.challenge(), now);
      session.setAttribute(PROFILE, new ProfileChange(account.getId(), account.getAuthVersion(), email, reauthEmail,
          phase, now.plus(REAUTH_LIFETIME), challenge));
    }
  }

  public void resendProfile(HttpServletRequest request, UserAccount account) {
    var session = requireSession(request);
    synchronized (WebUtils.getSessionMutex(session)) {
      var now = Instant.now();
      var pending = requireProfile(session, account, now);
      var challenge = send(request, pending.recipient(), pending.step().purpose, pending.challenge(), now);
      session.setAttribute(PROFILE, pending.withCode(pending.step(), pending.expiresAt(), challenge));
    }
  }

  public UserAccount verifyProfile(HttpServletRequest request, UserAccount account, String code) {
    var session = requireSession(request);
    synchronized (WebUtils.getSessionMutex(session)) {
      var now = Instant.now();
      var pending = requireProfile(session, account, now);
      if (pending.step() == ProfileStep.GOOGLE_EMAIL) {
        // Check the next destination's rate limit before consuming the proof from Google.
        otp.verify(pending.challenge(), code.strip(), true, now, () -> reserve(request, pending.email(), now));
        var challenge = sendReserved(pending.email(), ProfileStep.NEW_EMAIL.purpose, pending.challenge(), now);
        session.setAttribute(PROFILE, pending.withCode(ProfileStep.NEW_EMAIL, now.plus(REAUTH_LIFETIME), challenge));
        return null;
      }
      otp.verify(pending.challenge(), code.strip(), true, now);
      session.removeAttribute(PROFILE);
      return transactions.execute(status -> {
        var current = accounts.lockById(account.getId()).filter(CustomerAccountService::isCustomer)
            .orElseThrow(EmailOtpService::expired);
        if (current.getAuthVersion() != pending.version()
            || (pending.googleEmail() != null && !pending.googleEmail().equals(googleEmail(current.getId())))) {
          throw EmailOtpService.expired();
        }
        requireAvailable(pending.email(), current.getId());
        current.verifyEmail(pending.email(), now);
        current.invalidateAuthentication();
        return accounts.saveAndFlush(current);
      });
    }
  }

  public ProfileView profileView(HttpSession session, UserAccount account) {
    var pending = profile(session);
    if (pending == null || !pending.accountId().equals(account.getId()) || pending.version() != account.getAuthVersion()
        || !Instant.now().isBefore(pending.expiresAt())) return null;
    return new ProfileView(pending.step() == ProfileStep.GOOGLE_EMAIL, EmailOtpService.mask(pending.recipient()));
  }

  public void cancelProfile(HttpServletRequest request) {
    var session = requireSession(request);
    synchronized (WebUtils.getSessionMutex(session)) {
      var pending = profile(session);
      if (pending != null) pending.challenge().cancel();
      session.removeAttribute(PROFILE);
    }
  }

  public boolean usesGoogleReauthentication(UserAccount account) {
    return account.getUsername().startsWith("google:");
  }

  private String googleEmail(Long id) {
    return profiles.findById(id).filter(profile -> profile.getGoogleSubject() != null)
        .map(profile -> normalizeEmail(profile.getGoogleEmail())).orElse(null);
  }

  private ProfileChange requireProfile(HttpSession session, UserAccount account, Instant now) {
    var pending = profile(session);
    if (pending == null || !pending.accountId().equals(account.getId()) || pending.version() != account.getAuthVersion()
        || !now.isBefore(pending.expiresAt())
        || (pending.googleEmail() != null && !pending.googleEmail().equals(googleEmail(account.getId())))) {
      throw EmailOtpService.expired();
    }
    return pending;
  }

  private void requireAvailable(String email, Long owner) {
    if (emailInUse(email, owner)) throw new VerificationException("Email đã được sử dụng cho tài khoản khác.");
  }

  private Challenge send(HttpServletRequest request, String email, String purpose, Challenge previous, Instant now) {
    reserve(request, email, now);
    return sendReserved(email, purpose, previous, now);
  }

  private void reserve(HttpServletRequest request, String email, Instant now) {
    if (!otp.configured()) throw new VerificationException("Tạm thời chưa thể gửi mã qua email. Vui lòng thử lại sau.");
    try {
      limiter.reserveEmailVerification(email, request.getRemoteAddr(), now);
    } catch (SmsOtpRateLimiter.LimitReached exception) {
      throw rateLimited();
    }
  }

  private Challenge sendReserved(String email, String purpose, Challenge previous, Instant now) {
    var issued = otp.issue(previous, now);
    otp.schedule(() -> otp.send(issued, email, purpose, Instant.now()));
    return issued.challenge();
  }

  private static VerificationException rateLimited() {
    return new VerificationException("Bạn đã yêu cầu xác minh quá thường xuyên. Vui lòng chờ trước khi thử lại.");
  }

  private static HttpSession requireSession(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session == null) throw EmailOtpService.expired();
    return session;
  }

  private static Registration registration(HttpSession session) {
    return session == null ? null : (Registration) session.getAttribute(REGISTRATION);
  }

  private static ProfileChange profile(HttpSession session) {
    return session == null ? null : (ProfileChange) session.getAttribute(PROFILE);
  }

  public record Registered(UserAccount account, String target) {}
  public record ProfileView(boolean confirmGoogle, String maskedEmail) {}

  private record Registration(String name, String phone, String email, String encodedPassword, String target,
                              Challenge challenge) implements Serializable {}

  private enum ProfileStep {
    GOOGLE_EMAIL("xác thực lại tài khoản"), NEW_EMAIL("xác minh email mới");

    private final String purpose;

    ProfileStep(String purpose) { this.purpose = purpose; }
  }

  private record ProfileChange(Long accountId, long version, String email, String googleEmail, ProfileStep step,
                               Instant expiresAt, Challenge challenge) implements Serializable {
    String recipient() { return step == ProfileStep.GOOGLE_EMAIL ? googleEmail : email; }

    ProfileChange withCode(ProfileStep next, Instant deadline, Challenge code) {
      return new ProfileChange(accountId, version, email, googleEmail, next, deadline, code);
    }
  }
}
