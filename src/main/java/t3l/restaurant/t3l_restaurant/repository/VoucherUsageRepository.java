package t3l.restaurant.t3l_restaurant.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import t3l.restaurant.t3l_restaurant.entity.VoucherUsage;

public interface VoucherUsageRepository extends JpaRepository<VoucherUsage,Long> {
  Optional<VoucherUsage> findByActiveOrderIdAndStatus(Long orderId, String status);
  boolean existsByOrderId(Long orderId);
  List<VoucherUsage> findByVoucherIdOrderByIdDesc(Long voucherId, Pageable pageable);
  List<VoucherUsage> findByCustomerKeyAndStatusAndDemoFalseOrderByIdDesc(String customerKey,String status,Pageable pageable);

  @Query("""
      select count(u) from VoucherUsage u where u.voucherId=:id and
        (u.status='HELD' or (u.status='USED' and u.demo=false))
      """)
  long allocated(@Param("id") Long id);

  @Query("""
      select count(u) from VoucherUsage u where u.voucherId=:id and u.customerKey=:customer and
        (u.status='HELD' or (u.status='USED' and u.demo=false))
      """)
  long allocatedFor(@Param("id") Long id,@Param("customer") String customer);

  @Query("""
      select count(u) as used, coalesce(sum(u.discountAmount),0) as discount
      from VoucherUsage u where u.voucherId=:id and u.status='USED' and u.demo=false
      """)
  Totals totals(@Param("id") Long id);

  interface Totals {
    long getUsed();
    BigDecimal getDiscount();
  }
}
