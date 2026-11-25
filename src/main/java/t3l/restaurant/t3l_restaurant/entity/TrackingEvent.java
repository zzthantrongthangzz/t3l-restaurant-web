package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Getter
@Entity
@Table(name = "tracking_events", uniqueConstraints = @UniqueConstraint(name = "uk_tracking_event_key", columnNames = "eventKey"),
    indexes = {@Index(name = "ix_tracking_time_actor", columnList = "occurredAt,actorKind,eventType"),
        @Index(name = "ix_tracking_type_time", columnList = "eventType,occurredAt"),
        @Index(name = "ix_tracking_flow", columnList = "flowKey,eventType"),
        @Index(name = "ix_tracking_reservation", columnList = "reservationId,eventType")})
public class TrackingEvent {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "session_id", foreignKey = @ForeignKey(name = "fk_tracking_event_session"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  private TrackingSession session;
  @Column(nullable = false, length = 100) private String eventKey;
  @Column(nullable = false, length = 25) private String eventType;
  @Column(nullable = false) private LocalDateTime occurredAt;
  @Column(nullable = false, length = 8) private String actorKind;
  @Column(nullable = false, length = 30) private String pageKey;
  private Long dishId;
  @Column(length = 90) private String term;
  private Integer quantity;
  private Integer activeSeconds;
  @Column(length = 36) private String flowKey;
  private Long reservationId;
  private Boolean requiresDeposit;
}
