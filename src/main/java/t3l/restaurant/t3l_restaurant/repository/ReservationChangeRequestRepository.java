package t3l.restaurant.t3l_restaurant.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import t3l.restaurant.t3l_restaurant.entity.ReservationChangeRequest;

public interface ReservationChangeRequestRepository extends JpaRepository<ReservationChangeRequest, Long> {
  boolean existsByReservation_IdAndStatus(Long reservationId, String status);
  List<ReservationChangeRequest> findTop20ByReservation_IdOrderByCreatedAtDescIdDesc(Long reservationId);
  @EntityGraph(attributePaths = {"reservation", "reservation.table"})
  List<ReservationChangeRequest> findByStatusOrderByCreatedAtAscIdAsc(String status);
  @Query("select r.reservation.id from ReservationChangeRequest r where r.id = :id")
  Optional<Long> findReservationId(@Param("id") Long id);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from ReservationChangeRequest r where r.id = :id")
  Optional<ReservationChangeRequest> lockById(@Param("id") Long id);
  void deleteByReservation_IdIn(List<Long> reservationIds);
}
