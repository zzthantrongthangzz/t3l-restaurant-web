package t3l.restaurant.t3l_restaurant.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import t3l.restaurant.t3l_restaurant.entity.ReservationEmail;
import t3l.restaurant.t3l_restaurant.entity.ReservationEmail.Status;

public interface ReservationEmailRepository extends JpaRepository<ReservationEmail, Long> {
  boolean existsByEventKey(String eventKey);

  @Query("""
      select email.id from ReservationEmail email
      where email.status in :ready and email.nextAttemptAt <= :now
        and not exists (select prior.id from ReservationEmail prior
          where prior.reservation.id = email.reservation.id and prior.id < email.id and prior.status in :blocking)
      order by email.nextAttemptAt, email.id
      """)
  List<Long> findReadyIds(@Param("ready") Collection<Status> ready,
      @Param("blocking") Collection<Status> blocking, @Param("now") Instant now, Pageable page);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select email from ReservationEmail email where email.id = :id")
  Optional<ReservationEmail> lockById(@Param("id") Long id);

  @Modifying
  @Query("""
      update ReservationEmail email set email.status = :unknown, email.completedAt = :now,
        email.lastError = 'WORKER_INTERRUPTED'
      where email.status = :sending and email.lastAttemptAt < :cutoff
      """)
  int markInterrupted(@Param("sending") Status sending, @Param("unknown") Status unknown,
      @Param("cutoff") Instant cutoff, @Param("now") Instant now);
}
