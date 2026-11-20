package t3l.restaurant.t3l_restaurant.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import t3l.restaurant.t3l_restaurant.entity.RestaurantSection;

public interface RestaurantSectionRepository extends JpaRepository<RestaurantSection, Long> {
  List<RestaurantSection> findByKindOrderByNameAsc(String kind);
  Optional<RestaurantSection> findByKindAndNameIgnoreCase(String kind, String name);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from RestaurantSection s where s.kind = :kind order by s.id")
  List<RestaurantSection> lockByKind(@Param("kind") String kind);
}
