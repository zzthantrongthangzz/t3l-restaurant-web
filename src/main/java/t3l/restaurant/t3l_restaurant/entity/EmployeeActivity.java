package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "employee_activity", indexes = {
    @Index(name = "ix_employee_activity_time", columnList = "occurred_at,id"),
    @Index(name = "ix_employee_activity_actor_time", columnList = "actor,occurred_at,id"),
    @Index(name = "ix_employee_activity_target", columnList = "target_type,target_id,occurred_at")
})
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class EmployeeActivity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 80)
  private String actor;

  @Column(name = "actor_role", nullable = false, length = 16)
  private String actorRole;

  @Column(nullable = false, length = 80)
  private String operation;

  @Column(name = "target_type", nullable = false, length = 32)
  private String targetType;

  @Column(name = "target_id")
  private Long targetId;

  @Column(name = "target_label", nullable = false, length = 250)
  private String targetLabel;

  @Column(length = 500)
  private String details;

  @Column(name = "occurred_at", nullable = false)
  private LocalDateTime occurredAt;

  public EmployeeActivity(String actor, String actorRole, String operation, String targetType,
      Long targetId, String targetLabel, String details, LocalDateTime occurredAt) {
    this.actor = actor;
    this.actorRole = actorRole;
    this.operation = operation;
    this.targetType = targetType;
    this.targetId = targetId;
    this.targetLabel = targetLabel;
    this.details = details;
    this.occurredAt = occurredAt;
  }
}
