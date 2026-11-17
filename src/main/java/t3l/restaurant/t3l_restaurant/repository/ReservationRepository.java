package t3l.restaurant.t3l_restaurant.repository;

import java.time.LocalDate;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import t3l.restaurant.t3l_restaurant.entity.Reservation;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
  @EntityGraph(attributePaths = {"table"})
  java.util.Optional<Reservation> findByCode(String code);

  @Query("select r.id from Reservation r where r.code = :code")
  java.util.Optional<Long> findIdByCode(@Param("code") String code);

  @Override
  @EntityGraph(attributePaths = {"table"})
  List<Reservation> findAll(Sort sort);

  @EntityGraph(attributePaths = {"table"})
  List<Reservation> findByReservationDateBetweenAndStatusNot(
      LocalDate from, LocalDate to, String status);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Reservation r where r.id = :id")
  java.util.Optional<Reservation> lockById(@Param("id") Long id);
}
