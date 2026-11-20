package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "reservation_events", indexes =
    @Index(name = "ix_reservation_events_history", columnList = "reservation_id,id"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ReservationEvent {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "reservation_id", nullable = false, foreignKey = @ForeignKey(name = "fk_reservation_event_booking"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Reservation reservation;

  @Column(nullable = false, length = 80)
  private String actor;

  @Column(nullable = false, length = 500)
  private String description;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  public ReservationEvent(Reservation reservation, String actor, String description, LocalDateTime now) {
    this.reservation = reservation;
    this.actor = actor;
    this.description = description;
    this.createdAt = now;
  }
}
