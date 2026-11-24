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
  public static final int MAX_NOTE_LENGTH = 120;
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

  @Column(length = MAX_NOTE_LENGTH)
  private String note;


  @Column(nullable = false)
  private boolean cancelled;

  @Column(nullable = false)
  @org.hibernate.annotations.ColumnDefault("false")
  private boolean preordered;

  public DiningOrderItem(DiningOrder order, Dish dish, int quantity) {
    this(order, dish.getId(), dish.getName(), dish.getPrice(), quantity, false);
  }

  public DiningOrderItem(DiningOrder order, Dish dish, int quantity, String note, boolean preordered) {
    this(order, dish.getId(), dish.getName(), dish.getPrice(), quantity, preordered);
    String normalized = note == null ? "" : note.strip();
    if (normalized.length() > MAX_NOTE_LENGTH || normalized.contains("\r") || normalized.contains("\n")) {
      throw new IllegalArgumentException("Ghi chú món chỉ gồm một dòng, tối đa 120 ký tự.");
    }
    this.note = normalized.isEmpty() ? null : normalized;
  }

  public DiningOrderItem(DiningOrder order, Dish dish, int quantity, String note) {
    this(order, dish, quantity, note, false);
  }

  DiningOrderItem(DiningOrder order, Long dishId, String dishName, BigDecimal unitPrice, int quantity, boolean preordered) {
    this.order = order;
    this.dishId = dishId;
    this.dishName = dishName;
    this.unitPrice = unitPrice;
    this.quantity = quantity;
    this.preordered = preordered;
  }

  public BigDecimal getAmount() { return unitPrice.multiply(BigDecimal.valueOf(quantity)); }

  public void changeQuantity(int quantity) { this.quantity = quantity; }

  public void cancel() { cancelled = true; }
}
