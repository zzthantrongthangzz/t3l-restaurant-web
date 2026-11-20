package t3l.restaurant.t3l_restaurant.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import t3l.restaurant.t3l_restaurant.entity.DiningOrderItem;

public interface DiningOrderItemRepository extends JpaRepository<DiningOrderItem, Long> {
  boolean existsByDishId(Long dishId);
}
