package t3l.restaurant.t3l_restaurant.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import t3l.restaurant.t3l_restaurant.entity.DiningPayment;

public interface DiningPaymentRepository extends JpaRepository<DiningPayment, Long> {
  Optional<DiningPayment> findByOrder_Id(Long orderId);
  boolean existsByTransferReference(String reference);

  @EntityGraph(attributePaths = {"order", "order.reservation"})
  List<DiningPayment> findByShift_IdOrderByPaidAtDescIdDesc(Long shiftId);
}
