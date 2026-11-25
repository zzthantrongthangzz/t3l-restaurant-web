package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
@Entity
@Table(name="voucher_usages", uniqueConstraints=@UniqueConstraint(name="uk_voucher_usage_active_order",columnNames="active_order_id"),
    indexes={@Index(name="ix_voucher_usage_limits",columnList="voucher_id,status,demo,customer_key"),
        @Index(name="ix_voucher_usage_order",columnList="order_id")})
public class VoucherUsage {
  public static final String HELD="HELD";
  public static final String USED="USED";
  public static final String RELEASED="RELEASED";

  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @Column(name="voucher_id",nullable=false) private Long voucherId;
  @Column(name="order_id",nullable=false) private Long orderId;
  @Column(name="active_order_id") private Long activeOrderId;
  @Column(name="customer_key",length=80) private String customerKey;
  @Column(nullable=false,length=32) private String code;
  @Column(name="discount_type",nullable=false,length=10) private String discountType;
  @Column(name="discount_value",nullable=false,precision=18,scale=2) private BigDecimal discountValue;
  @Column(name="minimum_total",nullable=false,precision=16,scale=0) private BigDecimal minimumTotal;
  @Column(name="maximum_discount",precision=16,scale=0) private BigDecimal maximumDiscount;
  @Column(name="discount_amount",nullable=false,precision=16,scale=0) private BigDecimal discountAmount;
  @Column(nullable=false,length=10) private String status=HELD;
  @Column(name="held_at",nullable=false) private LocalDateTime heldAt;
  @Column(name="closed_at") private LocalDateTime closedAt;
  private boolean demo;

  public VoucherUsage(Voucher voucher, Long order, String customer, BigDecimal amount, LocalDateTime now) {
    voucherId=voucher.getId();
    orderId=order;
    activeOrderId=order;
    customerKey=customer;
    code=voucher.getCode();
    discountType=voucher.getDiscountType();
    discountValue=voucher.getDiscountValue();
    minimumTotal=voucher.getMinimumTotal();
    maximumDiscount=voucher.getMaximumDiscount();
    discountAmount=amount;
    heldAt=now;
  }

  public void reprice(BigDecimal amount) { discountAmount=amount; }
  public void release(LocalDateTime now) {
    status=RELEASED;
    activeOrderId=null;
    closedAt=now;
  }
  public void consume(LocalDateTime now, boolean simulated) {
    status=USED;
    demo=simulated;
    closedAt=now;
  }
}
