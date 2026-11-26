package t3l.restaurant.t3l_restaurant.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import t3l.restaurant.t3l_restaurant.entity.EmployeeActivity;

public interface EmployeeActivityRepository extends JpaRepository<EmployeeActivity, Long> {
  Page<EmployeeActivity> findByActorOrderByOccurredAtDescIdDesc(String actor, Pageable pageable);
}
