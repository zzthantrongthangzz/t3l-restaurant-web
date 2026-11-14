package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dishes")
@Getter
@NoArgsConstructor
public class Dish {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 140)
  private String name;

  @Column(nullable = false, length = 1000)
  private String description;

  @Column(nullable = false, precision = 12, scale = 0)
  private BigDecimal price;

  @Column(name = "image_url", length = 500)
  private String imageUrl;

  @Column(name = "image_public_id", length = 255)
  private String imagePublicId;

  @Column(name = "dietary_label", length = 80)
  private String dietaryLabel;

  @Column(name = "category_name", nullable = false, length = 80)
  private String categoryName;

  @Column(nullable = false)
  private boolean available = true;

  public void update(
      String name,
      String description,
      BigDecimal price,
      String imageUrl,
      String imagePublicId,
      String dietaryLabel,
      String categoryName,
      boolean available) {
    this.name = name;
    this.description = description;
    this.price = price;
    this.imageUrl = imageUrl;
    this.imagePublicId = imagePublicId;
    this.dietaryLabel = dietaryLabel;
    this.categoryName = categoryName;
    this.available = available;
  }
}
