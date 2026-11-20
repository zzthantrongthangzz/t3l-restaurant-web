package t3l.restaurant.t3l_restaurant.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Instant;
import org.springframework.stereotype.Service;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.service.GoogleCustomerService.GoogleProfile;
import t3l.restaurant.t3l_restaurant.service.SmsOtpService.OtpException;

@Service
public class CustomerRecovery {
  private static final String KEY = CustomerRecovery.class.getName();
  private final PhoneCustomerService phones;
  private final GoogleCustomerService google;
  private final SmsOtpRateLimiter limiter;

  public CustomerRecovery(PhoneCustomerService phones, GoogleCustomerService google, SmsOtpRateLimiter limiter) {
    this.phones = phones;
    this.google = google;
    this.limiter = limiter;
  }

  public void begin(HttpSession session, String phone, GoogleProfile identity, String target) {
    synchronized (session) {
      session.setAttribute(KEY, new Pending(phone, identity, target, Instant.now().plusSeconds(300), 0));
    }
  }

  public Pending pending(HttpSession session) {
    if (session != null && session.getAttribute(KEY) instanceof Pending pending
        && Instant.now().isBefore(pending.expires()) && pending.failures() < 5) return pending;
    return null;
  }

  public void clear(HttpSession session) {
    if (session != null) session.removeAttribute(KEY);
  }

  public Recovered complete(HttpServletRequest request, String username, String password, boolean newAccount) {
    HttpSession session = request.getSession(false);
    if (session == null) throw expired();
    synchronized (session) {
      Pending pending = pending(session);
      if (pending == null || (newAccount && pending.phone() == null)) throw expired();
      UserAccount account;
      if (newAccount) {
        account = phones.signIn(pending.phone(), false);
      } else {
        try {
          limiter.reserveRecovery(pending.phone() == null ? pending.google().subject() : pending.phone(),
              request.getRemoteAddr(), Instant.now());
        } catch (SmsOtpRateLimiter.LimitReached exception) {
          throw new OtpException("Có quá nhiều lần xác minh. Vui lòng thử lại sau.");
        }
        session.setAttribute(KEY, new Pending(pending.phone(), pending.google(), pending.target(),
            pending.expires(), pending.failures() + 1));
        account = pending.phone() != null ? phones.recover(pending.phone(), username, password)
            : google.recover(pending.google(), username, password);
      }
      session.removeAttribute(KEY);
      return new Recovered(account, pending.target());
    }
  }

  private OtpException expired() {
    return new OtpException("Phiên xác minh đã hết hạn. Hãy đăng nhập lại.");
  }

  public record Pending(String phone, GoogleProfile google, String target, Instant expires, int failures) implements Serializable {}
  public record Recovered(UserAccount account, String target) {}
}
