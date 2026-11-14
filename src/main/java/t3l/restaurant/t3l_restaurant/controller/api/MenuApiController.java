package t3l.restaurant.t3l_restaurant.controller.api;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import t3l.restaurant.t3l_restaurant.service.DishService;

@RestController
@RequestMapping("/api/menu")
@RequiredArgsConstructor
public class MenuApiController {
  private final DishService dishes;

  public record MenuItem(
      Long id,
      String name,
      String description,
      java.math.BigDecimal price,
      String category,
      boolean available) {}

  @GetMapping
  public java.util.List<MenuItem> list() {
    return dishes.findAvailable().stream()
        .map(d ->
            new MenuItem(
                d.getId(),
                d.getName(),
                d.getDescription(),
                d.getPrice(),
                d.getCategoryName(),
                d.isAvailable()))
        .toList();
  }
}
