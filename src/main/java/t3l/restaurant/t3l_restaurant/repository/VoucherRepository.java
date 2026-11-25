package t3l.restaurant.t3l_restaurant.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import t3l.restaurant.t3l_restaurant.entity.Voucher;

public interface VoucherRepository extends JpaRepository<Voucher,Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select v from Voucher v where v.code=:code")
  Optional<Voucher> lockCode(@Param("code") String code);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select v from Voucher v where v.id=:id")
  Optional<Voucher> lockId(@Param("id") Long id);

  Optional<Voucher> findByCode(String code);
  List<Voucher> findByPublicOfferTrueOrderByEndsAtAsc();
  List<Voucher> findAllByOrderByIdDesc();
}
