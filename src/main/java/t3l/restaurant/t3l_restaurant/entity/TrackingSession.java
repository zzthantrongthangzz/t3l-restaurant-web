package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
@Entity
@Table(name = "tracking_sessions", indexes = {
    @Index(name = "ix_tracking_browser_activity", columnList = "browserId,lastActivityAt"),
    @Index(name = "ix_tracking_session_expiry", columnList = "lastActivityAt"),
    @Index(name = "ix_tracking_user", columnList = "userId")
})
public class TrackingSession {
  @Id @Column(length = 36) private String id;
  @Column(nullable = false, length = 36) private String browserId;
  private Long userId;
  @Column(nullable = false) private LocalDateTime startedAt;
  @Column(nullable = false) private LocalDateTime lastActivityAt;
  private LocalDateTime endedAt;
  private LocalDateTime requestWindow;
  private int requestCount;
}
