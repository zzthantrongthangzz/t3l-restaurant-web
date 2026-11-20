package t3l.restaurant.t3l_restaurant.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import t3l.restaurant.t3l_restaurant.entity.CustomerProfile;

public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, Long> {
  @Query("select p.id from CustomerProfile p where p.googleSubject = :subject")
  Optional<Long> findAccountIdByGoogleSubject(@Param("subject") String subject);

  boolean existsByGoogleEmailIgnoreCase(String email);

  boolean existsByGoogleEmailIgnoreCaseAndIdNot(String email, Long id);
}
