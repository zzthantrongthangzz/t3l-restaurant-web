package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "restaurant_sections", uniqueConstraints =
    @UniqueConstraint(name = "uk_restaurant_section_kind_name", columnNames = {"kind", "name"}))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class RestaurantSection {
  public static final String AREA = "AREA";
  public static final String MENU = "MENU";

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(nullable = false, length = 8)
  private String kind;
  @Column(nullable = false, length = 80)
  private String name;
  @Column(nullable = false)
  private boolean active;

  public RestaurantSection(String kind, String name, boolean active) {
    this.kind = kind;
    update(name, active);
  }

  public void update(String name, boolean active) {
    this.name = name;
    this.active = active;
  }
}
