package t3l.restaurant.t3l_restaurant.repository;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import t3l.restaurant.t3l_restaurant.entity.DiningOrder;

public interface DiningOrderRepository extends JpaRepository<DiningOrder, Long> {
  @EntityGraph(attributePaths = "items")
  Optional<DiningOrder> findByReservation_Id(Long reservationId);

  List<DiningOrder> findByReservation_IdIn(Collection<Long> reservationIds);
  long countByStatus(String status);

  @Query("select o.reservation.id from DiningOrder o where o.id=:id")
  Optional<Long> findReservationId(@Param("id") Long id);
}
