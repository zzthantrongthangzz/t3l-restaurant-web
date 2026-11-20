package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dining_order_items", indexes =
    @Index(name = "ix_dining_items_order", columnList = "order_id,id"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class DiningOrderItem {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false, foreignKey = @ForeignKey(name = "fk_dining_item_order"))
  private DiningOrder order;

  // Retain the menu identity and the billed name/price even if the dish is removed.
  @Column(name = "dish_id", nullable = false)
  private Long dishId;

  @Column(name = "dish_name", nullable = false, length = 140)
  private String dishName;

  @Column(name = "unit_price", nullable = false, precision = 12, scale = 0)
  private BigDecimal unitPrice;

  @Column(nullable = false)
  private int quantity;

  @Column(nullable = false)
  private boolean cancelled;

  public DiningOrderItem(DiningOrder order, Dish dish, int quantity) {
    this.order = order;
    this.dishId = dish.getId();
    this.dishName = dish.getName();
    this.unitPrice = dish.getPrice();
    this.quantity = quantity;
  }

  public BigDecimal getAmount() { return unitPrice.multiply(BigDecimal.valueOf(quantity)); }

  public void changeQuantity(int quantity) { this.quantity = quantity; }

  public void cancel() { cancelled = true; }
}
