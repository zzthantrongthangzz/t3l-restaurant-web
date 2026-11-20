package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "restaurant_tables", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
@Getter
@NoArgsConstructor
public class RestaurantTable {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 32)
  private String code;

  @Column(name = "table_type", nullable = false, length = 40)
  private String tableType;

  @Column(name = "table_kind", length = 40)
  private String tableKind;

  @Column(nullable = false)
  private int capacity;

  @Column(nullable = false)
  private boolean active = true;

  public void setActive(boolean active) { this.active = active; }

  public void configure(String code, String area, String kind, int capacity, boolean active) {
    this.code = code;
    this.tableType = area;
    this.tableKind = kind;
    this.capacity = capacity;
    this.active = active;
  }
}
