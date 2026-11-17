package t3l.restaurant.t3l_restaurant.repository;

import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;

public interface RestaurantTableRepository extends JpaRepository<RestaurantTable, Long> {
  List<RestaurantTable> findByActiveTrueOrderByCodeAsc();

  // All allocation/status writers acquire these rows in the same order, until commit.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from RestaurantTable t where t.active = true order by t.id")
  List<RestaurantTable> lockActiveTables();
}
