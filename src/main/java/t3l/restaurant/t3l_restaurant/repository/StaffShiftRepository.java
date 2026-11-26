package t3l.restaurant.t3l_restaurant.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import t3l.restaurant.t3l_restaurant.entity.StaffShift;

public interface StaffShiftRepository extends JpaRepository<StaffShift, Long> {
  @EntityGraph(attributePaths = "employee")
  Optional<StaffShift> findFirstByEmployee_UsernameAndStatusOrderByOpenedAtDesc(
      String username, String status);

  @EntityGraph(attributePaths = "employee")
  @Query("""
      select s from StaffShift s
      where (:admin = true or s.employee.username = :username)
        and (:status = '' or s.status = :status)
      order by s.openedAt desc, s.id desc
      """)
  Page<StaffShift> findHistory(String username, boolean admin, String status, Pageable pageable);

  @EntityGraph(attributePaths = "employee")
  Optional<StaffShift> findOneById(Long id);
}
