package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "reservation_emails",
    uniqueConstraints = @UniqueConstraint(name = "uk_reservation_email_event", columnNames = "event_key"),
    indexes = {
        @Index(name = "ix_reservation_emails_ready", columnList = "status,next_attempt_at,id"),
        @Index(name = "ix_reservation_emails_booking", columnList = "reservation_id,id"),
        @Index(name = "ix_reservation_emails_in_progress", columnList = "status,last_attempt_at")
    })
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ReservationEmail {
  public static final String AWAITING_DEPOSIT = "Đã duyệt, chờ hoàn tất tiền cọc";

  public enum Kind {
    RECEIVED, CONFIRMED, REJECTED, CANCELLED, RESCHEDULED, CANCELLATION_APPROVED, CHANGE_DECLINED, CANCELLATION_DECLINED, NO_SHOW,
    DEPOSIT_VERIFIED, DEPOSIT_REJECTED, DEPOSIT_REFUNDED, DEPOSIT_EXPIRED, DEPOSIT_APPLIED,
    PREORDER_CHANGED, PREORDER_DECLINED, PREORDER_UNAVAILABLE
  }

  public enum Status {
    PENDING, SENDING, RETRY, SENT, FAILED, DELIVERY_UNKNOWN, SKIPPED_NO_RECIPIENT, SKIPPED_NO_SMTP
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "reservation_id", nullable = false, foreignKey = @ForeignKey(name = "fk_reservation_emails_reservation"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Reservation reservation;

  @Column(name = "event_key", nullable = false, length = 100)
  private String eventKey;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 32)
  private Kind kind;

  @Column(length = 254)
  private String recipient;

  @Column(name = "guest_name", nullable = false, length = 120)
  private String guestName;

  @Column(name = "booking_code", nullable = false, length = 20)
  private String bookingCode;

  @Column(name = "starts_at", nullable = false)
  private LocalDateTime startsAt;

  @Column(name = "guest_count", nullable = false)
  private int guestCount;

  @Column(nullable = false, length = 40)
  private String area;

  @Column(name = "booking_status", nullable = false, length = 40)
  private String bookingStatus;

  @Column(name = "review_note", length = 500)
  private String reviewNote;

  @Column(name = "deposit_summary", length = 400)
  private String depositSummary;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 32)
  private Status status;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "last_attempt_at")
  private Instant lastAttemptAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "last_error", length = 80)
  private String lastError;

  public ReservationEmail(Reservation reservation, String eventKey, Kind kind, String recipient,
      String reviewNote, Status status, Instant now) {
    this.reservation = reservation;
    this.eventKey = eventKey;
    this.kind = kind;
    this.recipient = recipient;
    this.guestName = reservation.getGuestName();
    this.bookingCode = reservation.getCode();
    this.startsAt = reservation.getStartsAt();
    this.guestCount = reservation.getGuestCount();
    this.area = reservation.getTable() == null ? "Chờ sắp xếp" : reservation.getTable().getKind().getLabel();
    this.bookingStatus = Reservation.CONFIRMED.equals(reservation.getStatus()) && reservation.isDepositOutstanding()
        ? AWAITING_DEPOSIT : reservation.getStatusLabel();
    this.reviewNote = reviewNote;
    if (reservation.getDepositAmount().signum() > 0 || reservation.getDepositPaid().signum() > 0) {
      var summary = new StringBuilder(reservation.getDepositStatusLabel())
          .append(". Đã nhận ").append(reservation.getDepositPaid().toPlainString()).append(" đ.");
      if ("WAITING_PAYMENT".equals(reservation.getDepositStatus()) && reservation.getDepositDueAt() != null) {
        summary.append(" Còn phải cọc ").append(reservation.getDepositRemaining().toPlainString()).append(" đ trước ")
            .append(reservation.getDepositDueAt().format(DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy"))).append('.');
      }
      if (reservation.getRefundRemaining().signum() > 0) summary.append(" Chờ hoàn ")
          .append(reservation.getRefundRemaining().toPlainString()).append(" đ.");
      if (reservation.getDepositRefunded().signum() > 0) summary.append(" Đã hoàn ")
          .append(reservation.getDepositRefunded().toPlainString()).append(" đ.");
      if (reservation.getDepositRetained().signum() > 0) summary.append(" Giữ cọc ")
          .append(reservation.getDepositRetained().toPlainString()).append(" đ.");
      this.depositSummary = summary.toString();
    }
    this.status = status;
    this.createdAt = now;
    if (status == Status.PENDING) nextAttemptAt = now;
    else completedAt = now;
  }

  public boolean isDue(Instant now) {
    return (status == Status.PENDING || status == Status.RETRY)
        && nextAttemptAt != null && !nextAttemptAt.isAfter(now);
  }

  public void claim(Instant now) {
    status = Status.SENDING;
    attempts++;
    lastAttemptAt = now;
    nextAttemptAt = null;
  }

  public void retryAt(Instant retryAt, String error) {
    status = Status.RETRY;
    nextAttemptAt = retryAt;
    lastError = error;
  }

  public void finish(Status finalStatus, Instant now, String error) {
    status = finalStatus;
    completedAt = now;
    lastError = error;
  }
}
