package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "reservations")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Reservation {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 20)
  private String code;

  @Column(name = "guest_name", nullable = false, length = 120)
  private String guestName;

  @Column(name = "guest_phone", nullable = false, length = 30)
  private String guestPhone;

  @Column(name = "guest_email", length = 180)
  private String guestEmail;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "table_id")
  private RestaurantTable table;

  @Column(name = "reservation_date", nullable = false)
  private LocalDate reservationDate;

  @Column(name = "reservation_time", nullable = false)
  private LocalTime reservationTime;

  @Column(name = "guest_count", nullable = false)
  private int guestCount;

  @Column(length = 500)
  private String note;

  @Column(nullable = false, length = 32)
  private String status = "PENDING_CONFIRMATION";

  @Column(name = "consent_accepted", nullable = false)
  private boolean consentAccepted;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();

  public Reservation(
      String code,
      String guestName,
      String guestPhone,
      String guestEmail,
      RestaurantTable table,
      LocalDate date,
      LocalTime time,
      int guestCount,
      String note,
      boolean consentAccepted) {
    this.code = code;
    this.guestName = guestName;
    this.guestPhone = guestPhone;
    this.guestEmail = guestEmail;
    this.table = table;
    this.reservationDate = date;
    this.reservationTime = time;
    this.guestCount = guestCount;
    this.note = note;
    this.consentAccepted = consentAccepted;
  }

  public void updateStatus(String status) {
    this.status = status;
  }
}
