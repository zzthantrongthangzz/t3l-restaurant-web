package t3l.restaurant.t3l_restaurant.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import t3l.restaurant.t3l_restaurant.security.LoginReturnTarget;

@Service
public class SmsOtpService {
  private static final String KEY = SmsOtpService.class.getName();
  private static final Duration LIFETIME = Duration.ofMinutes(5);
  private static final int MAX_FAILURES = 5;
  private final SecureRandom random = new SecureRandom();
  private final SecretKeySpec secret = new SecretKeySpec(random.generateSeed(32), "HmacSHA256");
  private final EsmsOtpSender sender;
  private final SmsOtpRateLimiter limiter;
  private final Clock clock;

  @Autowired
  public SmsOtpService(EsmsOtpSender sender, SmsOtpRateLimiter limiter) {
    this(sender, limiter, Clock.systemUTC());
  }

  SmsOtpService(EsmsOtpSender sender, SmsOtpRateLimiter limiter, Clock clock) {
    this.sender = sender;
    this.limiter = limiter;
    this.clock = clock;
  }

  public static String normalizePhone(String value) {
    if (value == null || value.length() > 30 || !value.strip().matches("\\+?[0-9 .()\\-]+")) return null;
    String digits = value.replaceAll("[^0-9]", "");
    if (digits.startsWith("84") && digits.length() == 11) digits = "0" + digits.substring(2);
    return digits.matches("0[35789][0-9]{8}") ? digits : null;
  }

  public boolean available(HttpServletRequest request) {
    return sender.live() || sender.local(request);
  }

  public boolean canLinkPhone() {
    return sender.live();
  }

  public Issue issue(HttpServletRequest request, Long accountId, String input, String returnTo) {
    String phone = normalizePhone(input);
    if (phone == null) throw new OtpException("Vui lòng nhập số di động Việt Nam hợp lệ.");
    boolean local = sender.local(request);
    if ((!local && !sender.live()) || (local && accountId != null)) {
      throw new OtpException("Đăng nhập SMS hiện chưa khả dụng.");
    }
    if (local && !sender.localPhone(phone)) {
      throw new OtpException("Chế độ cục bộ chỉ hỗ trợ số điện thoại kiểm thử đã cấu hình.");
    }
    var session = request.getSession();
    synchronized (session) {
      Instant now = clock.instant();
      try {
        limiter.reserve(phone, request.getRemoteAddr(), local, now);
      } catch (SmsOtpRateLimiter.LimitReached exception) {
        throw new OtpException("Bạn đã gửi mã quá thường xuyên. Vui lòng chờ trước khi thử lại.");
      }
      String id = UUID.randomUUID().toString();
      String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
      Challenge previous = challenge(session, accountId);
      if (!local && !sender.send(phone, code, id)) {
        throw new OtpException("Chưa thể yêu cầu eSMS gửi mã. Vui lòng thử lại sau.");
      }
      String target = LoginReturnTarget.validate(returnTo);
      if (target == null && previous != null) target = previous.proof().returnTo();
      var proof = new Proof(phone, accountId, target, local);
      session.setAttribute(KEY, new Challenge(proof, id, digest(id, code), clock.instant().plus(LIFETIME), 0));
      return new Issue(phone, local ? code : null);
    }
  }

  public String pendingPhone(HttpSession session, Long accountId) {
    if (session == null) return null;
    synchronized (session) {
      var challenge = challenge(session, accountId);
      return challenge == null ? null : challenge.proof().phone();
    }
  }

  public Proof verify(HttpServletRequest request, Long accountId, String submittedCode) {
    var session = request.getSession(false);
    if (session == null) throw expired();
    synchronized (session) {
      var challenge = challenge(session, accountId);
      if (challenge == null || (challenge.proof().local() && !sender.local(request))) throw expired();
      if (submittedCode != null && submittedCode.matches("[0-9]{6}")
          && MessageDigest.isEqual(challenge.hash(), digest(challenge.id(), submittedCode))) {
        session.removeAttribute(KEY);
        return challenge.proof();
      }
      int failures = challenge.failures() + 1;
      if (failures >= MAX_FAILURES) {
        session.removeAttribute(KEY);
        throw new OtpException("Bạn đã nhập sai quá nhiều lần. Hãy yêu cầu mã mới.");
      }
      session.setAttribute(KEY, new Challenge(challenge.proof(), challenge.id(), challenge.hash(), challenge.expiresAt(), failures));
      throw new OtpException("Mã xác thực chưa đúng. Bạn còn " + (MAX_FAILURES - failures) + " lần thử.");
    }
  }

  private Challenge challenge(HttpSession session, Long accountId) {
    if (session.getAttribute(KEY) instanceof Challenge challenge) {
      if (!clock.instant().isBefore(challenge.expiresAt())) session.removeAttribute(KEY);
      else if (Objects.equals(challenge.proof().accountId(), accountId)) return challenge;
    }
    return null;
  }

  private OtpException expired() {
    return new OtpException("Mã xác thực đã hết hạn hoặc đã được sử dụng. Hãy yêu cầu mã mới.");
  }

  private byte[] digest(String id, String code) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(secret);
      return mac.doFinal((id + ":" + code).getBytes(StandardCharsets.UTF_8));
    } catch (java.security.GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }

  public record Issue(String phone, String localCode) {}

  public record Proof(String phone, Long accountId, String returnTo, boolean local) implements Serializable {}

  private record Challenge(Proof proof, String id, byte[] hash, Instant expiresAt, int failures) implements Serializable {}

  public static class OtpException extends RuntimeException {
    public OtpException(String message) { super(message); }
  }
}
