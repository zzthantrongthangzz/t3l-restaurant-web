package t3l.restaurant.t3l_restaurant.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import t3l.restaurant.t3l_restaurant.entity.Dish;

public interface DishRepository extends JpaRepository<Dish, Long> {
  List<Dish> findByAvailableTrueOrderByCategoryNameAscNameAsc();

  java.util.Optional<Dish> findByName(String name);
}
