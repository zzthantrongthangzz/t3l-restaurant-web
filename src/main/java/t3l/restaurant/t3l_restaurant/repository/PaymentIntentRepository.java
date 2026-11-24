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
  Optional<PaymentIntent> findFirstByPaymentTypeAndOrderIdAndStatusInOrderByIdDesc(
      String paymentType, Long orderId, Collection<String> statuses);
  Optional<PaymentIntent> findFirstByPaymentTypeAndOrderIdOrderByIdDesc(String paymentType, Long orderId);
  Optional<PaymentIntent> findByPaymentCode(String paymentCode);

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
