package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dining_orders", uniqueConstraints =
    @UniqueConstraint(name = "uk_dining_order_reservation", columnNames = "reservation_id"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class DiningOrder {
  public static final String DRAFT = "DRAFT";
  public static final String FINALIZED = "FINALIZED";
  public static final String PAID = "PAID";
  public static final String VOID = "VOID";

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "reservation_id", nullable = false, foreignKey = @ForeignKey(name = "fk_dining_order_reservation"))
  private Reservation reservation;

  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
  @OrderBy("id ASC")
  private List<DiningOrderItem> items = new ArrayList<>();

  @Column(nullable = false, length = 16)
  private String status = DRAFT;

  @Column(name = "opened_by", nullable = false, length = 80)
  private String openedBy;

  @Column(name = "opened_at", nullable = false)
  private LocalDateTime openedAt;

  @Column(name = "finalized_at")
  private LocalDateTime finalizedAt;

  @Column(name = "total_amount", precision = 16, scale = 0)
  private BigDecimal totalAmount;

  @Column(name = "preorder_note", length = 500)
  private String preorderNote;

  public DiningOrder(Reservation reservation, String openedBy, LocalDateTime openedAt) {
    this.reservation = reservation;
    this.openedBy = openedBy;
    this.openedAt = openedAt;
  }

  public void addItem(Dish dish, int quantity) {
    addItem(dish, quantity, null);
  }

  public void addItem(Dish dish, int quantity, String note) {
    addItem(dish, quantity, note, false);
  }

  public void addItem(Dish dish, int quantity, String note, boolean preordered) {
    items.add(new DiningOrderItem(this, dish, quantity, note, preordered));
  }

  public List<DiningOrderItem> getPreorderItems() {
    return items.stream().filter(DiningOrderItem::isPreordered).filter(item -> !item.isCancelled()).toList();
  }

  public BigDecimal getPreorderTotal() {
    return getPreorderItems().stream().map(DiningOrderItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  public boolean isLegacyCustomerOrder() {
    return "CUSTOMER".equals(openedBy) && !items.isEmpty() && items.stream().noneMatch(DiningOrderItem::isPreordered);
  }

  public void addPreorderItem(Long dishId, String dishName, BigDecimal price, int quantity) {
    items.add(new DiningOrderItem(this, dishId, dishName, price, quantity, true));
  }

  public void setPreorderNote(String note) { preorderNote = note; }

  public BigDecimal getCurrentTotal() {
    return items.stream().filter(item -> !item.isCancelled()).map(DiningOrderItem::getAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  public boolean hasItems() {
    return items.stream().anyMatch(item -> !item.isCancelled());
  }

  public void finalizeOrder(LocalDateTime now) {
    totalAmount = getCurrentTotal();
    finalizedAt = now;
    status = FINALIZED;
  }

  public void markPaid() { status = PAID; }

  public void voidEmptyDraft() { status = VOID; }

  public String getInvoiceNumber() { return "T3L-HD-" + id; }

  public String getStatusLabel() {
    return switch (status) {
      case DRAFT -> "Đang gọi món";
      case FINALIZED -> "Chờ thanh toán";
      case PAID -> "Đã thanh toán";
      case VOID -> items.isEmpty() ? "Đã đóng đơn trống" : "Đã hủy đơn";
      default -> status;
    };
  }
}
