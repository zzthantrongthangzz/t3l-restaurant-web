package t3l.restaurant.t3l_restaurant.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import t3l.restaurant.t3l_restaurant.entity.StaffShift;

public interface StaffShiftRepository extends JpaRepository<StaffShift, Long> {
  Optional<StaffShift> findFirstByEmployee_UsernameAndStatusOrderByOpenedAtDesc(
      String username, String status);

  List<StaffShift> findTop20ByOrderByOpenedAtDesc();

  List<StaffShift> findTop20ByEmployee_UsernameOrderByOpenedAtDesc(String username);
}
