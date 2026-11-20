package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "reservations", indexes =
    @Index(name = "ix_reservations_customer_schedule", columnList = "customer_id,reservation_date,reservation_time"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Reservation {
  public static final String PENDING = "PENDING_CONFIRMATION";
  public static final String CONFIRMED = "CONFIRMED";
  public static final String CHECKED_IN = "CHECKED_IN";
  public static final String SEATED = "SEATED";
  public static final String COMPLETED = "COMPLETED";
  public static final String CANCELLED = "CANCELLED";
  public static final String NO_SHOW = "NO_SHOW";
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 20)
  private String code;

  @Column(name = "guest_name", nullable = false, length = 120)
  private String guestName;

  @Column(name = "guest_phone", nullable = false, length = 30)
  private String guestPhone;

  @Column(name = "guest_email", length = 254)
  private String guestEmail;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "table_id")
  private RestaurantTable table;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "customer_id", foreignKey = @ForeignKey(name = "fk_reservations_customer"))
  private UserAccount customer;

  @Column(name = "customer_id", insertable = false, updatable = false)
  private Long customerId;

  @Column(name = "reservation_date", nullable = false)
  private LocalDate reservationDate;

  @Column(name = "reservation_time", nullable = false)
  private LocalTime reservationTime;

  @Column(name = "ends_at")
  private LocalDateTime endsAt;

  @Column(name = "preferred_area", length = 40)
  private String preferredArea;

  @Column(name = "guest_count", nullable = false)
  private int guestCount;

  @Column(length = 500)
  private String note;

  @Column(nullable = false, length = 32)
  private String status = PENDING;

  @Column(name = "checked_in_at")
  private LocalDateTime checkedInAt;

  @Column(name = "seated_at")
  private LocalDateTime seatedAt;

  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "service_shift_id", foreignKey = @ForeignKey(name = "fk_reservations_service_shift"))
  private StaffShift serviceShift;

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

  public void checkIn(LocalDateTime now) {
    checkedInAt = now;
    status = CHECKED_IN;
  }

  public void seat(StaffShift shift, LocalDateTime now, LocalDateTime expectedEnd) {
    seatedAt = now;
    endsAt = expectedEnd;
    serviceShift = shift;
    status = SEATED;
  }

  public void complete(LocalDateTime now) {
    completedAt = now;
    status = COMPLETED;
  }

  public void moveTo(RestaurantTable destination, LocalDateTime expectedEnd) {
    table = destination;
    endsAt = expectedEnd;
  }

  public boolean isOpenBooking() {
    return PENDING.equals(status) || CONFIRMED.equals(status);
  }

  public boolean isTerminal() {
    return CANCELLED.equals(status) || COMPLETED.equals(status) || NO_SHOW.equals(status);
  }

  public void assignCustomer(UserAccount customer) {
    this.customer = customer;
    this.customerId = customer == null ? null : customer.getId();
  }

  public void setBookingDetails(LocalDateTime endsAt, String preferredArea) {
    this.endsAt = endsAt;
    this.preferredArea = preferredArea;
  }

  public void reschedule(RestaurantTable table, LocalDate date, LocalTime time,
      LocalDateTime end, int guests, String area) {
    this.table = table;
    this.reservationDate = date;
    this.reservationTime = time;
    this.endsAt = end;
    this.guestCount = guests;
    this.preferredArea = area == null || area.isBlank() ? null : area.trim();
    this.status = "CONFIRMED";
  }

  public LocalDateTime getStartsAt() {
    return LocalDateTime.of(reservationDate, reservationTime);
  }

  public LocalDateTime effectiveEndsAt(int durationMinutes) {
    return endsAt != null ? endsAt : getStartsAt().plusMinutes(durationMinutes);
  }

  public String getStatusLabel() {
    return labelFor(status);
  }

  public static String labelFor(String status) {
    return switch (status) {
      case PENDING -> "Chờ xác nhận";
      case CONFIRMED -> "Đã xác nhận";
      case CANCELLED -> "Đã hủy";
      case CHECKED_IN -> "Khách đã đến";
      case SEATED -> "Đang phục vụ";
      case COMPLETED -> "Hoàn thành";
      case NO_SHOW -> "Khách không đến";
      default -> status;
    };
  }
}
