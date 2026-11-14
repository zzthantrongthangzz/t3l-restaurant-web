package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "staff_shifts")
@Getter
@NoArgsConstructor
public class StaffShift {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "employee_id", nullable = false)
  private UserAccount employee;

  @Column(name = "opened_at", nullable = false)
  private LocalDateTime openedAt;

  @Column(name = "closed_at")
  private LocalDateTime closedAt;

  @Column(name = "opening_cash", nullable = false, precision = 14, scale = 0)
  private BigDecimal openingCash;

  @Column(name = "cash_sales", precision = 14, scale = 0)
  private BigDecimal cashSales;

  @Column(name = "other_sales", precision = 14, scale = 0)
  private BigDecimal otherSales;

  @Column(name = "counted_cash", precision = 14, scale = 0)
  private BigDecimal countedCash;

  @Column(name = "cash_difference", precision = 14, scale = 0)
  private BigDecimal cashDifference;

  @Column(nullable = false, length = 16)
  private String status = "OPEN";

  public StaffShift(UserAccount employee, BigDecimal openingCash) {
    this.employee = employee;
    this.openingCash = openingCash;
    this.openedAt = LocalDateTime.now();
  }

  public void close(BigDecimal cashSales, BigDecimal otherSales, BigDecimal countedCash) {
    this.cashSales = cashSales;
    this.otherSales = otherSales;
    this.countedCash = countedCash;
    this.cashDifference = countedCash.subtract(openingCash).subtract(cashSales);
    this.closedAt = LocalDateTime.now();
    this.status = "CLOSED";
  }
}
