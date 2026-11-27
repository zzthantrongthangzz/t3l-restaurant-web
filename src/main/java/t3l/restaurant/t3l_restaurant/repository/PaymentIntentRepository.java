package t3l.restaurant.t3l_restaurant.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import t3l.restaurant.t3l_restaurant.entity.PaymentIntent;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {
  @Query("select p.reservationId from PaymentIntent p where p.id = :id")
  Optional<Long> findReservationId(@Param("id") Long id);

  boolean existsByPaymentCode(String paymentCode);
  boolean existsByProviderTransactionId(String providerTransactionId);

  Optional<PaymentIntent> findFirstByPaymentTypeAndReservationIdAndStatusInOrderByIdDesc(
      String paymentType, Long reservationId, Collection<String> statuses);
  Optional<PaymentIntent> findFirstByPaymentTypeAndReservationIdOrderByIdDesc(String paymentType, Long reservationId);
  @Query("""
      select p from PaymentIntent p
      where p.paymentType = 'DEPOSIT' and p.reservationId in :ids
        and p.id = (select max(latest.id) from PaymentIntent latest
          where latest.paymentType = 'DEPOSIT' and latest.reservationId = p.reservationId)
      """)
  List<PaymentIntent> latestDeposits(@Param("ids") Collection<Long> reservationIds);
  Optional<PaymentIntent> findFirstByPaymentTypeAndOrderIdAndStatusInOrderByIdDesc(
      String paymentType, Long orderId, Collection<String> statuses);
  Optional<PaymentIntent> findFirstByPaymentTypeAndOrderIdOrderByIdDesc(String paymentType, Long orderId);
  Optional<PaymentIntent> findByPaymentCode(String paymentCode);

  @Query("""
      select count(p) from PaymentIntent p, DiningOrder o
      where p.paymentType = 'INVOICE' and p.status = 'REVIEW_REQUIRED'
        and p.orderId = o.id and o.status = 'FINALIZED' and o.reservation.serviceShift.id = :shiftId
        and p.id = (select max(latest.id) from PaymentIntent latest
          where latest.orderId = o.id and latest.paymentType = 'INVOICE')
      """)
  long countInvoicesToReview(@Param("shiftId") Long shiftId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from PaymentIntent p where p.id=:id")
  Optional<PaymentIntent> lockById(@Param("id") Long id);

  @Query("""
      select p from PaymentIntent p
      where p.status in ('PENDING','EXPIRED','CLOSED') and p.expiresAt >= :lateCutoff and p.id > :afterId
      order by p.id
      """)
  List<PaymentIntent> findPollableAfter(@Param("lateCutoff") LocalDateTime lateCutoff,
      @Param("afterId") Long afterId, Pageable page);
}
