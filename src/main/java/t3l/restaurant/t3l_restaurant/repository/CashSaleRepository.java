package t3l.restaurant.t3l_restaurant.repository;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import t3l.restaurant.t3l_restaurant.entity.CashSale;

public interface CashSaleRepository extends JpaRepository<CashSale, Long> {
  List<CashSale> findByShift_IdOrderByCreatedAtDesc(Long shiftId);

  @Query("select coalesce(sum(s.totalAmount), 0) from CashSale s where s.shift.id = :shiftId and s.paymentMethod = :method")
  BigDecimal totalByShiftAndMethod(@Param("shiftId") Long shiftId, @Param("method") String method);
}
