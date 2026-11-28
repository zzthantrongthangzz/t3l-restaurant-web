package t3l.restaurant.t3l_restaurant.service;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SmsOtpRateLimiter {
  private final JdbcTemplate jdbc;

  public SmsOtpRateLimiter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public void reserve(String phone, String address, boolean local, Instant now) {
    String scope = local ? "test:" : "live:";
    take(scope + "global", 120, Duration.ofHours(1), Duration.ZERO, now);
    take(scope + "ip:" + address, 12, Duration.ofHours(1), Duration.ZERO, now);
    take(scope + "phone:" + phone, 4, Duration.ofHours(1), Duration.ofSeconds(60), now);
  }

  @Transactional
  public void reserveRecovery(String identity, String address, Instant now) {
    take("recovery:global", 200, Duration.ofHours(1), Duration.ZERO, now);
    take("recovery:ip:" + address, 20, Duration.ofHours(1), Duration.ZERO, now);
    take("recovery:id:" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
        8, Duration.ofHours(1), Duration.ZERO, now);
  }

  @Transactional
  public void reservePasswordReset(String phone, String address, Instant now) {
    take("password-reset:global", 120, Duration.ofHours(1), Duration.ZERO, now);
    take("password-reset:ip:" + address, 12, Duration.ofHours(1), Duration.ZERO, now);
    take("password-reset:phone:" + phone, 4, Duration.ofHours(1), Duration.ofMinutes(1), now);
  }

  private void take(String key, int maxHits, Duration window, Duration cooldown, Instant now) {
    Timestamp time = Timestamp.from(now);
    jdbc.update("""
        INSERT INTO sms_otp_limits (rate_key, hits, expires_at, last_hit_at) VALUES (?, 0, ?, ?)
        ON DUPLICATE KEY UPDATE rate_key = rate_key
        """, key, time, time);
    var bucket = jdbc.queryForObject(
        "SELECT hits, expires_at, last_hit_at FROM sms_otp_limits WHERE rate_key = ? FOR UPDATE",
        (row, index) -> new Bucket(row.getInt(1), row.getTimestamp(2).toInstant(), row.getTimestamp(3).toInstant()), key);
    boolean expired = !now.isBefore(bucket.expiresAt());
    if (bucket.hits() != 0 && ((!expired && bucket.hits() >= maxHits) || now.isBefore(bucket.lastHitAt().plus(cooldown)))) {
      throw new LimitReached();
    }
    if (expired && key.endsWith(":global")) {
      jdbc.update("DELETE FROM sms_otp_limits WHERE expires_at < ?", Timestamp.from(now.minus(Duration.ofHours(1))));
    }
    jdbc.update("UPDATE sms_otp_limits SET hits = ?, expires_at = ?, last_hit_at = ? WHERE rate_key = ?",
        expired ? 1 : bucket.hits() + 1, Timestamp.from(expired ? now.plus(window) : bucket.expiresAt()), time, key);
  }

  private record Bucket(int hits, Instant expiresAt, Instant lastHitAt) {}

  public static final class LimitReached extends RuntimeException {}
}
