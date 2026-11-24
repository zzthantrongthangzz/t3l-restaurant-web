package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
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

  @Column(name = "deposit_amount", nullable = false, precision = 16, scale = 0)
  private BigDecimal depositAmount = BigDecimal.ZERO;

  @Column(name = "deposit_status", nullable = false, length = 32)
  private String depositStatus = "NOT_REQUIRED";

  @Column(name = "deposit_demo", nullable = false)
  @org.hibernate.annotations.ColumnDefault("false")
  private boolean depositDemo;

  @Column(name = "deposit_reference", length = 80)
  private String depositReference;

  @Column(name = "deposit_paid", nullable = false, precision = 16, scale = 0)
  private BigDecimal depositPaid = BigDecimal.ZERO;

  @Column(name = "deposit_submitted_amount", precision = 16, scale = 0)
  private BigDecimal depositSubmittedAmount;

  @Column(name = "deposit_due_at")
  private LocalDateTime depositDueAt;

  @Column(name = "deposit_review_due_at")
  private LocalDateTime depositReviewDueAt;

  @Column(name = "deposit_refund", nullable = false, precision = 16, scale = 0)
  private BigDecimal depositRefund = BigDecimal.ZERO;

  @Column(name = "deposit_refunded", nullable = false, precision = 16, scale = 0)
  private BigDecimal depositRefunded = BigDecimal.ZERO;

  @Column(name = "deposit_retained", nullable = false, precision = 16, scale = 0)
  private BigDecimal depositRetained = BigDecimal.ZERO;

  @Column(name = "deposit_closed_at")
  private LocalDateTime depositClosedAt;

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

  public void priceDeposit(BigDecimal amount, LocalDateTime due) {
    depositAmount = amount;
    if (depositPaid.compareTo(amount) >= 0) {
      depositStatus = depositPaid.signum() == 0 ? "NOT_REQUIRED" : "PAID";
      depositDueAt = null;
    } else {
      depositStatus = "WAITING_PAYMENT";
      if (depositDueAt == null || due.isBefore(depositDueAt)) depositDueAt = due;
    }
  }

  public void waitForStaffBeforeDeposit() {
    if ("WAITING_PAYMENT".equals(depositStatus)) {
      depositStatus = "PENDING_STAFF_CONFIRMATION";
      depositDueAt = null;
    }
  }

  public void approveDepositRequirement(LocalDateTime due) {
    if ("PENDING_STAFF_CONFIRMATION".equals(depositStatus)) priceDeposit(depositAmount, due);
  }

  public boolean isDepositOutstanding() {
    return getDepositRemaining().signum() > 0;
  }

  public BigDecimal getDepositRemaining() { return depositAmount.subtract(depositPaid).max(BigDecimal.ZERO); }
  public BigDecimal getRefundRemaining() { return depositRefund.subtract(depositRefunded); }
  public BigDecimal getAvailableDeposit() { return "PAID".equals(depositStatus) ? depositPaid : BigDecimal.ZERO; }

  public boolean hasExpiredDeposit(LocalDateTime now) {
    if (!PENDING.equals(status) && !CONFIRMED.equals(status)) return false;
    LocalDateTime deadline = switch (depositStatus) {
      case "WAITING_PAYMENT" -> depositDueAt;
      case "TRANSFER_SUBMITTED" -> depositReviewDueAt;
      default -> null;
    };
    return deadline != null && !now.isBefore(deadline);
  }

  public void submitDepositReference(String reference, LocalDateTime reviewDue) {
    depositReference = reference;
    depositSubmittedAmount = getDepositRemaining();
    depositReviewDueAt = reviewDue;
    depositStatus = "TRANSFER_SUBMITTED";
  }

  public void verifyDeposit(boolean received) {
    boolean closed = "CLOSED_TRANSFER_PENDING".equals(depositStatus);
    if (received) {
      depositPaid = depositPaid.add(depositSubmittedAmount);
      if (closed) depositRefund = depositRefund.add(depositSubmittedAmount);
    }
    depositSubmittedAmount = null;
    depositReviewDueAt = null;
    if (!received) depositReference = null;
    if (closed) depositStatus = closedDepositStatus("EXPIRED");
    else if (received) {
      depositStatus = "PAID";
      depositDueAt = null;
    } else depositStatus = "WAITING_PAYMENT";
  }

  public boolean receiveVerifiedDeposit(BigDecimal amount, String transactionReference, boolean demo) {
    if (!List.of("WAITING_PAYMENT", "TRANSFER_SUBMITTED").contains(depositStatus)
        || amount == null || amount.compareTo(getDepositRemaining()) != 0 || amount.signum() <= 0
        || (!PENDING.equals(status) && !CONFIRMED.equals(status)) || depositClosedAt != null) return false;
    depositPaid = depositPaid.add(amount);
    depositReference = transactionReference;
    depositSubmittedAmount = null;
    depositReviewDueAt = null;
    depositDueAt = null;
    depositDemo = depositDemo || demo;
    depositStatus = "PAID";
    return true;
  }

  public void closeDeposit(int refundPercent, boolean expired, LocalDateTime now) {
    if (depositClosedAt != null || (depositAmount.signum() == 0 && depositPaid.signum() == 0)) return;
    depositRefund = depositPaid.multiply(BigDecimal.valueOf(refundPercent)).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
    depositRetained = depositPaid.subtract(depositRefund);
    depositClosedAt = now;
    depositDueAt = null;
    depositReviewDueAt = null;
    depositStatus = depositSubmittedAmount != null ? "CLOSED_TRANSFER_PENDING"
        : closedDepositStatus(expired ? "EXPIRED" : "CLOSED");
  }

  public void settleDeposit(BigDecimal applied, LocalDateTime now) {
    if (getAvailableDeposit().signum() == 0) return;
    depositRefund = depositPaid.subtract(applied);
    depositClosedAt = now;
    depositStatus = closedDepositStatus("APPLIED");
  }

  public void markDepositRefunded() {
    depositRefunded = depositRefund;
    depositStatus = "REFUNDED";
  }

  private String closedDepositStatus(String fallback) {
    if (getRefundRemaining().signum() > 0) return "REFUND_PENDING";
    if (depositRefunded.signum() > 0) return "REFUNDED";
    return depositRetained.signum() > 0 ? "FORFEITED" : fallback;
  }

  public String getDepositStatusLabel() {
    return switch (depositStatus) {
      case "PENDING_STAFF_CONFIRMATION" -> "Chờ duyệt đặt bàn";
      case "WAITING_PAYMENT" -> "Chờ thanh toán cọc";
      case "TRANSFER_SUBMITTED" -> "Chờ xác nhận giao dịch";
      case "PAYMENT_REVIEW_REQUIRED" -> "Cần đối soát tiền cọc";
      case "PAID" -> "Đã thanh toán cọc";
      case "APPLIED" -> "Đã khấu trừ vào hóa đơn";
      case "REFUND_PENDING" -> "Chờ hoàn tiền cọc";
      case "REFUNDED" -> "Đã hoàn tiền cọc";
      case "FORFEITED" -> "Đã giữ cọc theo chính sách";
      case "CLOSED_TRANSFER_PENDING" -> "Đã giải phóng bàn, chờ đối soát giao dịch";
      case "EXPIRED" -> "Đã hết hạn thanh toán cọc";
      case "CLOSED" -> "Đã đóng yêu cầu cọc";
      default -> "Không yêu cầu cọc";
    };
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
