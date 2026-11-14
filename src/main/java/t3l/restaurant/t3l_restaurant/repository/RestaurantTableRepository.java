package t3l.restaurant.t3l_restaurant.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;

public interface RestaurantTableRepository extends JpaRepository<RestaurantTable, Long> {
  List<RestaurantTable> findByActiveTrueOrderByCodeAsc();
}
