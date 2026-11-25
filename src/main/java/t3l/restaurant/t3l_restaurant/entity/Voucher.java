package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import t3l.restaurant.t3l_restaurant.dto.VoucherForm;

@Getter
@Entity
@Table(name="vouchers", uniqueConstraints=@UniqueConstraint(name="uk_voucher_code", columnNames="code"),
    indexes=@Index(name="ix_voucher_public", columnList="public_offer,enabled,ends_at"))
public class Voucher {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @Column(nullable=false,length=32) private String code;
  @Column(nullable=false,length=120) private String title;
  @Column(name="discount_type",nullable=false,length=10) private String discountType;
  @Column(name="discount_value",nullable=false,precision=18,scale=2) private BigDecimal discountValue;
  @Column(name="minimum_total",nullable=false,precision=16,scale=0) private BigDecimal minimumTotal;
  @Column(name="maximum_discount",precision=16,scale=0) private BigDecimal maximumDiscount;
  @Column(name="starts_at",nullable=false) private LocalDateTime startsAt;
  @Column(name="ends_at",nullable=false) private LocalDateTime endsAt;
  @Column(name="total_limit") private Integer totalLimit;
  @Column(name="customer_limit") private Integer customerLimit;
  @Column(name="public_offer",nullable=false) private boolean publicOffer;
  private boolean enabled;

  public void update(VoucherForm form, String normalizedCode) {
    code=normalizedCode;
    title=form.getTitle().strip();
    discountType=form.getDiscountType();
    discountValue=form.getDiscountValue();
    minimumTotal=form.getMinimumTotal();
    maximumDiscount=form.getMaximumDiscount();
    startsAt=form.getStartsAt();
    endsAt=form.getEndsAt();
    totalLimit=form.getTotalLimit();
    customerLimit=form.getCustomerLimit();
    publicOffer=form.isPublicOffer();
    enabled=form.isEnabled();
  }

  public void setEnabled(boolean enabled) { this.enabled=enabled; }

  public String state(LocalDateTime now) {
    if (!enabled) return "DISABLED";
    if (now.isBefore(startsAt)) return "SCHEDULED";
    return now.isBefore(endsAt) ? "ACTIVE" : "EXPIRED";
  }

  public String getOfferLabel() {
    return discountValue.stripTrailingZeros().toPlainString() + ("PERCENT".equals(discountType) ? "%" : " ₫");
  }

  public VoucherForm form() {
    var form = new VoucherForm();
    form.setCode(code);
    form.setTitle(title);
    form.setDiscountType(discountType);
    form.setDiscountValue(discountValue.stripTrailingZeros());
    form.setMinimumTotal(minimumTotal);
    form.setMaximumDiscount(maximumDiscount);
    form.setStartsAt(startsAt);
    form.setEndsAt(endsAt);
    form.setTotalLimit(totalLimit);
    form.setCustomerLimit(customerLimit);
    form.setPublicOffer(publicOffer);
    form.setEnabled(enabled);
    return form;
  }
}
