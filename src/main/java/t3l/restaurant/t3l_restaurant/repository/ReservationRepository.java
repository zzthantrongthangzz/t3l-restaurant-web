package t3l.restaurant.t3l_restaurant.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import t3l.restaurant.t3l_restaurant.entity.Reservation;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
  @EntityGraph(attributePaths = {"table"})
  java.util.Optional<Reservation> findByCode(String code);

  @Override
  @EntityGraph(attributePaths = {"table"})
  List<Reservation> findAll(Sort sort);

  @EntityGraph(attributePaths = {"table"})
  List<Reservation> findByReservationDateAndStatusNot(LocalDate date, String status);

  boolean existsByTable_IdAndReservationDateAndStatusNot(
      Long tableId, LocalDate date, String status);
}
