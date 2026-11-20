package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "sms_otp_limits", indexes = @Index(name = "ix_sms_otp_limits_expiry", columnList = "expires_at"))
public class SmsOtpLimit {
  protected SmsOtpLimit() {}

  @Id
  @Column(name = "rate_key", length = 100)
  private String key;

  @Column(nullable = false)
  private int hits;

  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;

  @Column(name = "last_hit_at", nullable = false)
  private LocalDateTime lastHitAt;
}
