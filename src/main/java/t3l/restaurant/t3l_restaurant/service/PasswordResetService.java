package t3l.restaurant.t3l_restaurant.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.WebUtils;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.CustomerProfileRepository;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;
import t3l.restaurant.t3l_restaurant.security.LoginReturnTarget;

@Slf4j
@Service
public class PasswordResetService {
  private static final String KEY = PasswordResetService.class.getName();
  private static final Duration LIFETIME = Duration.ofMinutes(5);
  private static final int MAX_ATTEMPTS = 5;
  private final OtpCodes codes = new OtpCodes();
  private final PhoneCustomerService phones;
  private final UserAccountRepository accounts;
  private final CustomerProfileRepository profiles;
  private final SmsOtpRateLimiter limiter;
  private final PasswordEncoder passwords;
  private final TransactionTemplate transactions;
  private final TaskExecutor executor;
  private final JavaMailSender mail;
  private final String from;
  private final Clock clock;

  @Autowired
  public PasswordResetService(PhoneCustomerService phones, UserAccountRepository accounts,
      CustomerProfileRepository profiles, SmsOtpRateLimiter limiter, PasswordEncoder passwords,
      TransactionTemplate transactions, @Qualifier("applicationTaskExecutor") TaskExecutor executor,
      JavaMailSender mail, @Value("${spring.mail.username:}") String username,
      @Value("${spring.mail.password:}") String mailPassword) {
    this(phones, accounts, profiles, limiter, passwords, transactions, executor, mail,
        mailPassword.isBlank() ? null : ReservationEmailQueue.mailbox(username.strip()), Clock.systemUTC());
  }

  PasswordResetService(PhoneCustomerService phones, UserAccountRepository accounts, CustomerProfileRepository profiles,
      SmsOtpRateLimiter limiter, PasswordEncoder passwords, TransactionTemplate transactions, TaskExecutor executor,
      JavaMailSender mail, String from, Clock clock) {
    this.phones = phones;
    this.accounts = accounts;
    this.profiles = profiles;
    this.limiter = limiter;
    this.passwords = passwords;
    this.transactions = transactions;
    this.executor = executor;
    this.mail = mail;
    this.from = from;
    this.clock = clock;
  }

  public View view(HttpSession session) {
    if (session == null) return new View(Step.PHONE, null, null);
    synchronized (WebUtils.getSessionMutex(session)) {
      Flow flow = flow(session);
      if (flow == null) return new View(Step.PHONE, null, null);
      if (flow.grant() == null) return new View(Step.CODE, flow.target(), null);
      if (clock.instant().isBefore(flow.grant().expiresAt())) {
        return new View(Step.PASSWORD, flow.target(), mask(flow.grant().recipient().email()));
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
      Challenge challenge = flow == null ? null : flow.challenge();
      Instant now = clock.instant();
      if (challenge == null || !now.isBefore(challenge.expiresAt)) throw expired();
      if (challenge.attempts >= MAX_ATTEMPTS) throw attemptsExceeded();
      boolean matches = codes.matches(challenge.id, challenge.hash, code);
      Recipient recipient = challenge.recipient;
      if (matches && recipient != null) {
        request.changeSessionId();
        challenge.cancelled = true;
        session.setAttribute(KEY, new Flow(flow.phone(), flow.target(), null, new Grant(recipient, now.plus(LIFETIME))));
        return;
      }
      if (++challenge.attempts >= MAX_ATTEMPTS) throw attemptsExceeded();
      throw new ResetException("Mã xác minh chưa đúng. Bạn còn " + (MAX_ATTEMPTS - challenge.attempts) + " lần thử.");
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
    Challenge old = previous == null ? null : previous.challenge();
    String id = UUID.randomUUID().toString();
    String code = codes.generate();
    if (old != null) {
      while (codes.matches(old.id, old.hash, code)) code = codes.generate();
      old.cancelled = true;
    }
    var challenge = new Challenge(id, codes.digest(id, code), now.plus(LIFETIME));
    session.setAttribute(KEY, new Flow(phone, target, challenge, null));
    String deliveryCode = code;
    try {
      // Account lookup and SMTP stay off the HTTP path for every phone number.
      executor.execute(() -> deliver(phone, challenge, deliveryCode));
    } catch (TaskRejectedException exception) {
      log.warn("Password reset email task could not be scheduled");
    }
  }

  private void deliver(String phone, Challenge challenge, String code) {
    if (from == null || !deliverable(challenge)) return;
    try {
      Recipient recipient = recipientFor(phone);
      if (recipient == null || !deliverable(challenge)) return;
      var message = new SimpleMailMessage();
      message.setFrom(from);
      message.setTo(recipient.email());
      message.setSubject("T3L Restaurant | Mã đặt lại mật khẩu");
      message.setText("Mã xác minh đặt lại mật khẩu của bạn: " + code
          + "\n\nMã có hiệu lực trong 5 phút và chỉ dùng một lần."
          + "\nNếu bạn không yêu cầu đổi mật khẩu, hãy bỏ qua email này. Không chia sẻ mã với người khác.");
      mail.send(message);
      challenge.recipient = recipient;
    } catch (RuntimeException exception) {
      log.warn("Password reset email delivery was not confirmed");
    }
  }

  private boolean deliverable(Challenge challenge) {
    return !challenge.cancelled && clock.instant().isBefore(challenge.expiresAt);
  }

  private Recipient recipientFor(String phone) {
    var account = phones.findPasswordAccount(phone).filter(PasswordResetService::eligible).orElse(null);
    if (account == null) return null;
    // Only the address from a verified Google identity is trusted for recovery.
    String email = profiles.findById(account.getId()).filter(profile -> profile.getGoogleSubject() != null)
        .map(profile -> ReservationEmailQueue.mailbox(profile.getGoogleEmail())).orElse(null);
    return email == null ? null : new Recipient(account.getId(), account.getAuthVersion(), email);
  }

  private static boolean eligible(UserAccount account) {
    String username = account.getUsername();
    return CustomerAccountService.isCustomer(account) && account.getArchivedAt() == null
        && !username.startsWith("google:") && !username.startsWith("desk:") && !PhoneCustomerService.isLocal(account);
  }

  private static String mask(String email) {
    int at = email.indexOf('@');
    int dot = email.lastIndexOf('.');
    int prefixLength = Character.charCount(email.codePointAt(0));
    int domainStart = at + 1;
    int domainPrefix = Character.charCount(email.codePointAt(domainStart));
    String local = at > prefixLength ? email.substring(0, prefixLength) : "";
    String domain = dot > domainStart + domainPrefix ? email.substring(domainStart, domainStart + domainPrefix) : "";
    return local + "***@" + domain + "***" + email.substring(dot);
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

  private static ResetException attemptsExceeded() {
    return new ResetException("Bạn đã nhập sai quá nhiều lần. Vui lòng yêu cầu mã mới.");
  }

  public enum Step { PHONE, CODE, PASSWORD }

  public record View(Step step, String target, String maskedEmail) {}

  public record Completed(String username, String target) {}

  private record Recipient(Long accountId, long authVersion, String email) implements Serializable {}

  private record Grant(Recipient recipient, Instant expiresAt) implements Serializable {}

  private record Flow(String phone, String target, Challenge challenge, Grant grant) implements Serializable {}

  private static final class Challenge implements Serializable {
    private final String id;
    private final byte[] hash;
    private final Instant expiresAt;
    private int attempts;
    private volatile boolean cancelled;
    private volatile Recipient recipient;

    private Challenge(String id, byte[] hash, Instant expiresAt) {
      this.id = id;
      this.hash = hash;
      this.expiresAt = expiresAt;
    }
  }

  public static final class ResetException extends RuntimeException {
    ResetException(String message) { super(message); }
  }
}
