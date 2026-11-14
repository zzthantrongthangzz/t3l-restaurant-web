package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "cash_sales")
@Getter
@NoArgsConstructor
public class CashSale {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "shift_id", nullable = false)
  private StaffShift shift;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "dish_id", nullable = false)
  private Dish dish;

  @Column(name = "dish_name", nullable = false, length = 140)
  private String dishName;

  @Column(nullable = false)
  private int quantity;

  @Column(name = "unit_price", nullable = false, precision = 12, scale = 0)
  private BigDecimal unitPrice;

  @Column(name = "total_amount", nullable = false, precision = 14, scale = 0)
  private BigDecimal totalAmount;

  @Column(name = "payment_method", nullable = false, length = 20)
  private String paymentMethod;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();

  public CashSale(StaffShift shift, Dish dish, int quantity, String paymentMethod) {
    this.shift = shift;
    this.dish = dish;
    this.dishName = dish.getName();
    this.quantity = quantity;
    this.unitPrice = dish.getPrice();
    this.totalAmount = dish.getPrice().multiply(BigDecimal.valueOf(quantity));
    this.paymentMethod = paymentMethod;
  }
}
