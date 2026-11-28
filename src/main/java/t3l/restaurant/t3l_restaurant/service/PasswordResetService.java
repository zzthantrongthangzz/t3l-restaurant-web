package t3l.restaurant.t3l_restaurant.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.WebUtils;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.CustomerProfileRepository;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;
import t3l.restaurant.t3l_restaurant.security.LoginReturnTarget;

@Service
public class PasswordResetService {
  private static final String KEY = PasswordResetService.class.getName();
  private final PhoneCustomerService phones;
  private final UserAccountRepository accounts;
  private final CustomerProfileRepository profiles;
  private final SmsOtpRateLimiter limiter;
  private final PasswordEncoder passwords;
  private final TransactionTemplate transactions;
  private final EmailOtpService otp;
  private final Clock clock;

  @Autowired
  public PasswordResetService(PhoneCustomerService phones, UserAccountRepository accounts,
      CustomerProfileRepository profiles, SmsOtpRateLimiter limiter, PasswordEncoder passwords,
      TransactionTemplate transactions, EmailOtpService otp) {
    this(phones, accounts, profiles, limiter, passwords, transactions, otp, Clock.systemUTC());
  }

  PasswordResetService(PhoneCustomerService phones, UserAccountRepository accounts, CustomerProfileRepository profiles,
      SmsOtpRateLimiter limiter, PasswordEncoder passwords, TransactionTemplate transactions, EmailOtpService otp,
      Clock clock) {
    this.phones = phones;
    this.accounts = accounts;
    this.profiles = profiles;
    this.limiter = limiter;
    this.passwords = passwords;
    this.transactions = transactions;
    this.otp = otp;
    this.clock = clock;
  }

  public View view(HttpSession session) {
    if (session == null) return new View(Step.PHONE, null, null);
    synchronized (WebUtils.getSessionMutex(session)) {
      Flow flow = flow(session);
      if (flow == null) return new View(Step.PHONE, null, null);
      if (flow.grant() == null) return new View(Step.CODE, flow.target(), null);
      if (clock.instant().isBefore(flow.grant().expiresAt())) {
        return new View(Step.PASSWORD, flow.target(), EmailOtpService.mask(flow.grant().recipient().email()));
      }
      session.removeAttribute(KEY);
      return new View(Step.PHONE, flow.target(), null);
    }
  }

  public void start(HttpServletRequest request, String input, String returnTo) {
    String phone = SmsOtpService.normalizePhone(input);
    if (phone == null) throw new ResetException("Vui lòng nhập số di động Việt Nam hợp lệ.");
    var session = request.getSession();
    synchronized (WebUtils.getSessionMutex(session)) {
      issue(session, phone, LoginReturnTarget.validate(returnTo), request.getRemoteAddr());
    }
  }

  public void resend(HttpServletRequest request) {
    var session = requireSession(request);
    synchronized (WebUtils.getSessionMutex(session)) {
      Flow flow = flow(session);
      if (flow == null || flow.challenge() == null) throw expired();
      issue(session, flow.phone(), flow.target(), request.getRemoteAddr());
    }
  }

  public void verify(HttpServletRequest request, String code) {
    var session = requireSession(request);
    synchronized (WebUtils.getSessionMutex(session)) {
      Flow flow = flow(session);
      PendingCode pending = flow == null ? null : flow.challenge();
      Instant now = clock.instant();
      Recipient recipient = pending == null ? null : pending.recipient;
      try {
        otp.verify(pending == null ? null : pending.challenge, code, recipient != null, now);
      } catch (EmailOtpService.VerificationException exception) {
        throw new ResetException(exception.getMessage());
      }
      request.changeSessionId();
      session.setAttribute(KEY, new Flow(flow.phone(), flow.target(), null,
          new Grant(recipient, now.plus(EmailOtpService.LIFETIME))));
    }
  }

  public Completed reset(HttpServletRequest request, String password, String confirmation) {
    var session = requireSession(request);
    synchronized (WebUtils.getSessionMutex(session)) {
      Flow flow = flow(session);
      Grant grant = flow == null ? null : flow.grant();
      if (grant == null || !clock.instant().isBefore(grant.expiresAt())) throw expired();
      if (password.isBlank() || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
        throw new ResetException("Mật khẩu phải có ít nhất 12 ký tự và không vượt quá 72 byte UTF-8.");
      }
      if (!password.equals(confirmation)) throw new ResetException("Mật khẩu xác nhận không khớp.");
      String encoded = passwords.encode(password);
      String username;
      try {
        username = transactions.execute(status -> {
          var account = accounts.lockById(grant.recipient().accountId()).orElseThrow(PasswordResetService::expired);
          if (!clock.instant().isBefore(grant.expiresAt()) || !grant.recipient().equals(recipientFor(flow.phone()))) {
            throw expired();
          }
          account.manage(account.getRole(), true, encoded);
          return account.getUsername();
        });
      } catch (ResetException exception) {
        session.removeAttribute(KEY);
        throw exception;
      }
      session.removeAttribute(KEY);
      return new Completed(username, flow.target());
    }
  }

  private void issue(HttpSession session, String phone, String target, String address) {
    Instant now = clock.instant();
    try {
      limiter.reservePasswordReset(phone, address, now);
    } catch (SmsOtpRateLimiter.LimitReached exception) {
      throw new ResetException("Bạn đã yêu cầu mã quá thường xuyên. Vui lòng chờ trước khi thử lại.");
    }
    Flow previous = flow(session);
    PendingCode old = previous == null ? null : previous.challenge();
    var issued = otp.issue(old == null ? null : old.challenge, now);
    var pending = new PendingCode(issued.challenge());
    session.setAttribute(KEY, new Flow(phone, target, pending, null));
    // Account lookup and SMTP stay off the HTTP path for every phone number.
    otp.schedule(() -> deliver(phone, pending, issued));
  }

  private void deliver(String phone, PendingCode pending, EmailOtpService.Issued issued) {
    if (otp.configured() && pending.challenge.active(clock.instant())) {
      var recipient = recipientFor(phone);
      if (recipient != null) {
        pending.recipient = recipient;
        otp.send(issued, recipient.email(), "đặt lại mật khẩu", clock.instant());
      }
    }
  }

  private Recipient recipientFor(String phone) {
    var account = phones.findPasswordAccount(phone).filter(PasswordResetService::eligible).orElse(null);
    if (account == null) return null;
    String email = account.getEmailVerifiedAt() != null ? ReservationEmailQueue.mailbox(account.getEmail())
        : profiles.findById(account.getId()).filter(profile -> profile.getGoogleSubject() != null)
            .map(profile -> ReservationEmailQueue.mailbox(profile.getGoogleEmail())).orElse(null);
    return email == null ? null : new Recipient(account.getId(), account.getAuthVersion(), email);
  }

  private static boolean eligible(UserAccount account) {
    String username = account.getUsername();
    return CustomerAccountService.isCustomer(account) && account.getArchivedAt() == null
        && !username.startsWith("google:") && !username.startsWith("desk:")
        && (!PhoneCustomerService.isLocal(account) || account.getEmailVerifiedAt() != null);
  }

  private static HttpSession requireSession(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session == null) throw expired();
    return session;
  }

  private static Flow flow(HttpSession session) {
    return (Flow) session.getAttribute(KEY);
  }

  private static ResetException expired() {
    return new ResetException("Mã hoặc phiên xác minh đã hết hiệu lực. Vui lòng yêu cầu mã mới.");
  }

  public enum Step { PHONE, CODE, PASSWORD }

  public record View(Step step, String target, String maskedEmail) {}

  public record Completed(String username, String target) {}

  private record Recipient(Long accountId, long authVersion, String email) implements Serializable {}

  private record Grant(Recipient recipient, Instant expiresAt) implements Serializable {}

  private record Flow(String phone, String target, PendingCode challenge, Grant grant) implements Serializable {}

  private static final class PendingCode implements Serializable {
    private final EmailOtpService.Challenge challenge;
    private volatile Recipient recipient;

    private PendingCode(EmailOtpService.Challenge challenge) {
      this.challenge = challenge;
    }
  }

  public static final class ResetException extends RuntimeException {
    ResetException(String message) { super(message); }
  }
}
