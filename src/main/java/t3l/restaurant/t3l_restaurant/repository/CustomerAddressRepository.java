package t3l.restaurant.t3l_restaurant.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import t3l.restaurant.t3l_restaurant.entity.CustomerAddress;

public interface CustomerAddressRepository extends JpaRepository<CustomerAddress, Long> {
  List<CustomerAddress> findByAccount_IdOrderByDefaultAddressDescIdAsc(Long accountId);
  Optional<CustomerAddress> findByIdAndAccount_Id(Long id, Long accountId);
}
