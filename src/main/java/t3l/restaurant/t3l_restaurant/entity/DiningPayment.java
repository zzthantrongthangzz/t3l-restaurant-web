package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dining_payments", uniqueConstraints = {
    @UniqueConstraint(name = "uk_dining_payment_order", columnNames = "order_id"),
    @UniqueConstraint(name = "uk_dining_payment_transfer", columnNames = "transfer_reference")
}, indexes = @Index(name = "ix_dining_payment_shift", columnList = "shift_id,paid_at"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class DiningPayment {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false, foreignKey = @ForeignKey(name = "fk_dining_payment_order"))
  private DiningOrder order;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "shift_id", nullable = false, foreignKey = @ForeignKey(name = "fk_dining_payment_shift"))
  private StaffShift shift;

  @Column(nullable = false, precision = 16, scale = 0)
  private BigDecimal amount;

  @Column(name = "payment_method", nullable = false, length = 20)
  private String paymentMethod;

  @Column(name = "transfer_reference", length = 80)
  private String transferReference;

  @Column(name = "received_by", nullable = false, length = 80)
  private String receivedBy;

  @Column(name = "paid_at", nullable = false)
  private LocalDateTime paidAt;

  public DiningPayment(DiningOrder order, StaffShift shift, String method, String reference,
      String receivedBy, LocalDateTime now) {
    this.order = order;
    this.shift = shift;
    this.amount = order.getTotalAmount();
    this.paymentMethod = method;
    this.transferReference = reference;
    this.receivedBy = receivedBy;
    this.paidAt = now;
  }

  public String getMethodLabel() { return "CASH".equals(paymentMethod) ? "Tiền mặt" : "Chuyển khoản"; }
}
