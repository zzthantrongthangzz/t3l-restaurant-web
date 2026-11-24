package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_intents", uniqueConstraints = {
    @UniqueConstraint(name = "uk_payment_intent_code", columnNames = "payment_code"),
    @UniqueConstraint(name = "uk_payment_intent_provider_tx", columnNames = "provider_transaction_id")
}, indexes = {
    @Index(name = "ix_payment_intent_reservation", columnList = "reservation_id,payment_type,id"),
    @Index(name = "ix_payment_intent_order", columnList = "order_id,payment_type,id"),
    @Index(name = "ix_payment_intent_poll", columnList = "status,expires_at")
})
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class PaymentIntent {
  public static final String DEPOSIT = "DEPOSIT";
  public static final String INVOICE = "INVOICE";
  public static final String PENDING = "PENDING";
  public static final String RECEIVED = "RECEIVED";
  public static final String APPLIED = "APPLIED";
  public static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";
  public static final String EXPIRED = "EXPIRED";
  public static final String CLOSED = "CLOSED";

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "payment_code", nullable = false, length = 12)
  private String paymentCode;

  @Column(name = "payment_type", nullable = false, length = 16)
  private String paymentType;

  @Column(name = "reservation_id")
  private Long reservationId;

  @Column(name = "order_id")
  private Long orderId;

  @Column(nullable = false, precision = 16, scale = 0)
  private BigDecimal amount;

  @Column(nullable = false, length = 24)
  private String status = PENDING;

  @Column(name = "is_demo", nullable = false)
  private boolean demo;

  @Column(name = "provider_transaction_id", length = 80)
  private String providerTransactionId;

  @Column(name = "provider_reference", length = 100)
  private String providerReference;

  @Column(name = "received_account", length = 80)
  private String receivedAccount;

  @Column(name = "transaction_content", length = 255)
  private String transactionContent;

  @Column(name = "transaction_at")
  private LocalDateTime transactionAt;

  @Column(name = "review_reason", length = 120)
  private String reviewReason;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;

  @Column(name = "received_at")
  private LocalDateTime receivedAt;

  @Column(name = "applied_at")
  private LocalDateTime appliedAt;

  public PaymentIntent(String paymentCode, String paymentType, Long reservationId, Long orderId,
      BigDecimal amount, LocalDateTime createdAt, LocalDateTime expiresAt) {
    this.paymentCode = paymentCode;
    this.paymentType = paymentType;
    this.reservationId = reservationId;
    this.orderId = orderId;
    this.amount = amount;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  public void markReceived(String transactionId, String reference, String account, String content,
      LocalDateTime transactionAt, LocalDateTime receivedAt, boolean demo) {
    if (RECEIVED.equals(status) || APPLIED.equals(status)) return;
    this.providerTransactionId = transactionId;
    this.providerReference = reference;
    this.receivedAccount = account;
    this.transactionContent = content;
    this.transactionAt = transactionAt;
    this.receivedAt = receivedAt;
    this.demo = demo;
    this.reviewReason = null;
    this.status = RECEIVED;
  }

  public void requireReview(String transactionId, String reference, String account, String content,
      LocalDateTime transactionAt, String reason, boolean demo) {
    if (RECEIVED.equals(status) || APPLIED.equals(status)) return;
    this.providerTransactionId = transactionId;
    this.providerReference = reference;
    this.receivedAccount = account;
    this.transactionContent = content;
    this.transactionAt = transactionAt;
    this.reviewReason = reason;
    this.demo = demo;
    this.status = REVIEW_REQUIRED;
  }

  public void markApplied(LocalDateTime now) {
    if (!RECEIVED.equals(status)) throw new IllegalStateException("Chỉ có thể áp dụng giao dịch đã xác minh.");
    this.status = APPLIED;
    this.appliedAt = now;
  }

  public void markExpired() {
    if (PENDING.equals(status)) status = EXPIRED;
  }

  public void close() {
    if (PENDING.equals(status) || EXPIRED.equals(status)) status = CLOSED;
  }
}
