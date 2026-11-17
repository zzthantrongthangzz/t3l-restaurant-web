package t3l.restaurant.t3l_restaurant.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
  Optional<UserAccount> findByUsername(String username);

  boolean existsByUsername(String username);

  boolean existsByUsernameIgnoreCase(String username);

  boolean existsByEmailIgnoreCase(String email);
}
