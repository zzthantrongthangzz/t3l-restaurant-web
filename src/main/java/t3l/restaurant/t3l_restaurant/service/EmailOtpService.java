package t3l.restaurant.t3l_restaurant.service;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmailOtpService {
  static final Duration LIFETIME = Duration.ofMinutes(5);
  private static final int MAX_ATTEMPTS = 5;
  private final OtpCodes codes = new OtpCodes();
  private final TaskExecutor executor;
  private final JavaMailSender mail;
  private final String from;

  public EmailOtpService(@Qualifier("applicationTaskExecutor") TaskExecutor executor, JavaMailSender mail,
      @Value("${spring.mail.username:}") String username, @Value("${spring.mail.password:}") String password) {
    this.executor = executor;
    this.mail = mail;
    from = password.isBlank() ? null : ReservationEmailQueue.mailbox(username.strip());
  }

  boolean configured() {
    return from != null;
  }

  Issued issue(Challenge previous, Instant now) {
    String id = UUID.randomUUID().toString();
    String code = codes.generate();
    if (previous != null) {
      while (codes.matches(previous.id, previous.hash, code)) code = codes.generate();
      previous.cancel();
    }
    return new Issued(new Challenge(id, codes.digest(id, code), now.plus(LIFETIME)), code);
  }

  void verify(Challenge challenge, String code, boolean canVerify, Instant now) {
    verify(challenge, code, canVerify, now, () -> {});
  }

  void verify(Challenge challenge, String code, boolean canVerify, Instant now, Runnable onMatch) {
    if (challenge == null || !challenge.active(now)) throw expired();
    if (challenge.attempts >= MAX_ATTEMPTS) throw attemptsExceeded();
    boolean matches = codes.matches(challenge.id, challenge.hash, code);
    if (matches && canVerify && challenge.delivered) {
      onMatch.run();
      challenge.cancel();
      return;
    }
    if (++challenge.attempts >= MAX_ATTEMPTS) throw attemptsExceeded();
    throw new VerificationException("Mã xác minh chưa đúng. Bạn còn " + (MAX_ATTEMPTS - challenge.attempts) + " lần thử.");
  }

  void schedule(Runnable delivery) {
    try {
      executor.execute(() -> {
        try {
          delivery.run();
        } catch (RuntimeException exception) {
          log.warn("Email OTP delivery could not be completed");
        }
      });
    } catch (TaskRejectedException exception) {
      log.warn("Email OTP task could not be scheduled");
    }
  }

  void send(Issued issued, String recipient, String purpose, Instant now) {
    if (!configured() || !issued.challenge().active(now)) return;
    try {
      var message = new SimpleMailMessage();
      message.setFrom(from);
      message.setTo(recipient);
      message.setSubject("T3L Restaurant | Mã " + purpose);
      message.setText("Mã xác minh " + purpose + " của bạn: " + issued.code()
          + "\n\nMã có hiệu lực trong 5 phút và chỉ dùng một lần."
          + "\nNếu bạn không yêu cầu mã này, hãy bỏ qua email. Không chia sẻ mã với người khác.");
      mail.send(message);
      issued.challenge().delivered = true;
    } catch (RuntimeException exception) {
      log.warn("Email OTP delivery was not confirmed");
    }
  }

  static String mask(String email) {
    int at = email.indexOf('@');
    int dot = email.lastIndexOf('.');
    int initial = Character.charCount(email.codePointAt(0));
    int domain = at + 1;
    int domainInitial = Character.charCount(email.codePointAt(domain));
    String localPrefix = at > initial ? email.substring(0, initial) : "";
    String domainPrefix = dot > domain + domainInitial ? email.substring(domain, domain + domainInitial) : "";
    return localPrefix + "***@" + domainPrefix + "***" + email.substring(dot);
  }

  static VerificationException expired() {
    return new VerificationException("Mã hoặc phiên xác minh đã hết hiệu lực. Vui lòng yêu cầu mã mới.");
  }

  private static VerificationException attemptsExceeded() {
    return new VerificationException("Bạn đã nhập sai quá nhiều lần. Vui lòng yêu cầu mã mới.");
  }

  record Issued(Challenge challenge, String code) {}

  static final class Challenge implements Serializable {
    private final String id;
    private final byte[] hash;
    private final Instant expiresAt;
    private int attempts;
    private volatile boolean delivered;
    private volatile boolean cancelled;

    private Challenge(String id, byte[] hash, Instant expiresAt) {
      this.id = id;
      this.hash = hash;
      this.expiresAt = expiresAt;
    }

    boolean active(Instant now) {
      return !cancelled && now.isBefore(expiresAt);
    }

    void cancel() {
      cancelled = true;
    }
  }

  public static final class VerificationException extends RuntimeException {
    VerificationException(String message) { super(message); }
  }
}
