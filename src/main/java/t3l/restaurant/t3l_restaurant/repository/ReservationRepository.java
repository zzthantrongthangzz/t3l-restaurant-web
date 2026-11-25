package t3l.restaurant.t3l_restaurant.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import t3l.restaurant.t3l_restaurant.entity.Reservation;

public interface ReservationRepository extends JpaRepository<Reservation, Long>, JpaSpecificationExecutor<Reservation> {
  @EntityGraph(attributePaths = {"table"})
  java.util.Optional<Reservation> findByCode(String code);

  @Query(value = """
      SELECT r.* FROM reservations r
      WHERE r.customer_id IS NULL
        AND CASE
          WHEN REGEXP_REPLACE(r.guest_phone, '[^0-9]', '') LIKE '84%'
            AND CHAR_LENGTH(REGEXP_REPLACE(r.guest_phone, '[^0-9]', '')) = 11
          THEN CONCAT('0', SUBSTRING(REGEXP_REPLACE(r.guest_phone, '[^0-9]', ''), 3))
          ELSE REGEXP_REPLACE(r.guest_phone, '[^0-9]', '')
        END = :phone
      ORDER BY r.reservation_date DESC, r.reservation_time DESC, r.id DESC
      LIMIT 20
      """, nativeQuery = true)
  List<Reservation> findTop20UnclaimedByCanonicalPhone(@Param("phone") String phone);

  List<Reservation> findByCustomerIdOrderByReservationDateAscReservationTimeAscIdAsc(Long customerId);

  @Query("select r.id from Reservation r where r.code = :code")
  java.util.Optional<Long> findIdByCode(@Param("code") String code);

  @Override
  @EntityGraph(attributePaths = {"table"})
  List<Reservation> findAll(Sort sort);

  @EntityGraph(attributePaths = {"table"})
  List<Reservation> findByReservationDateBetweenAndStatusNot(
      LocalDate from, LocalDate to, String status);

  @Override
  @EntityGraph(attributePaths = "table")
  Page<Reservation> findAll(Specification<Reservation> spec, Pageable page);

  @EntityGraph(attributePaths = "table")
  Optional<Reservation> findOneById(Long id);

  long countByStatus(String status);

  @EntityGraph(attributePaths = "table")
  List<Reservation> findByReservationDateOrderByReservationTimeAscIdAsc(LocalDate date);

  @EntityGraph(attributePaths = "table")
  List<Reservation> findByStatusInAndTableIsNotNull(Collection<String> statuses);

  List<Reservation> findByStatusInAndReservationDateGreaterThanEqual(Collection<String> statuses, LocalDate date);

  @Query("""
      select r.id from Reservation r where r.status in ('PENDING_CONFIRMATION','CONFIRMED')
        and r.depositPaid < r.depositAmount and
        ((r.depositStatus = 'WAITING_PAYMENT' and r.depositDueAt <= :now)
          or (r.depositStatus = 'TRANSFER_SUBMITTED' and r.depositReviewDueAt <= :now))
      order by r.id
      """)
  List<Long> expiredDepositIds(@Param("now") LocalDateTime now, Pageable page);

  @EntityGraph(attributePaths = "table")
  List<Reservation> findByTable_IdAndStatusInAndReservationDateGreaterThanEqual(
      Long tableId, Collection<String> statuses, LocalDate date);

  @Modifying
  @Query("""
      update Reservation r set r.preferredArea = :next where r.preferredArea = :previous
      and r.status in ('PENDING_CONFIRMATION','CONFIRMED','CHECKED_IN','SEATED')
      """)
  int renamePreferredArea(@Param("previous") String previous, @Param("next") String next);

  @EntityGraph(attributePaths = "table")
  List<Reservation> findTop10ByStatusInAndReservationDateGreaterThanOrderByReservationDateAscReservationTimeAscIdAsc(
      Collection<String> statuses, LocalDate date);

  @EntityGraph(attributePaths = "table")
  List<Reservation> findTop8ByStatusOrderByReservationDateAscReservationTimeAscIdAsc(String status);

  @Query("select coalesce(sum(r.guestCount), 0) from Reservation r where r.serviceShift.id = :shiftId")
  long guestsServedByShift(@Param("shiftId") Long shiftId);

  @Query("""
      select coalesce(sum(r.guestCount), 0) from Reservation r
      where (r.checkedInAt >= :start and r.checkedInAt < :end)
      or (r.checkedInAt is null and r.reservationDate = :date and r.status in ('CHECKED_IN', 'SEATED', 'COMPLETED'))
      """)
  long guestsArrivedOn(@Param("date") LocalDate date, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Reservation r where r.id = :id")
  java.util.Optional<Reservation> lockById(@Param("id") Long id);
}
