package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "admin_activity", indexes = @Index(name = "ix_admin_activity_time", columnList = "occurred_at,id"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class AdminActivity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(nullable = false, length = 80)
  private String actor;
  @Column(nullable = false, length = 32)
  private String operation;
  @Column(nullable = false, length = 250)
  private String target;
  @Column(name = "occurred_at", nullable = false)
  private LocalDateTime occurredAt;

  public AdminActivity(String actor, String operation, String target, LocalDateTime occurredAt) {
    this.actor = actor;
    this.operation = operation;
    this.target = target;
    this.occurredAt = occurredAt;
  }
}
