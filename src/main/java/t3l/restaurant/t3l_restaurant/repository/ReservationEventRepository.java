package t3l.restaurant.t3l_restaurant.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import t3l.restaurant.t3l_restaurant.entity.ReservationEvent;

public interface ReservationEventRepository extends JpaRepository<ReservationEvent, Long> {
  List<ReservationEvent> findByReservation_IdOrderByIdDesc(Long reservationId);
}
