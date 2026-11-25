package t3l.restaurant.t3l_restaurant.repository;

import java.util.Optional;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
  Optional<UserAccount> findByUsername(String username);

  Optional<UserAccount> findByUsernameIgnoreCase(String username);

  Optional<UserAccount> findByVerifiedPhone(String verifiedPhone);

  Optional<UserAccount> findByEmailIgnoreCase(String email);

  @Query(value = """
      SELECT * FROM app_users WHERE role = 'BUYER'
        AND REGEXP_REPLACE(phone, '[^0-9]', '') IN (:number, :alternate)
      ORDER BY id LIMIT 2
      """, nativeQuery = true)
  List<UserAccount> findBuyersByContactPhone(@Param("number") String number, @Param("alternate") String alternate);

  @Query(value = """
      SELECT COUNT(*) FROM app_users a
      WHERE a.role = 'BUYER' AND a.enabled = true AND a.verified_phone IS NULL AND a.username NOT LIKE 'local-sms:%'
        AND REGEXP_REPLACE(a.phone, '[^0-9]', '') IN (:national, :international)
        AND NOT EXISTS (SELECT 1 FROM customer_profiles p WHERE p.user_id = a.id AND p.google_subject IS NOT NULL)
      """, nativeQuery = true)
  long countLegacyPhoneMatches(@Param("national") String national, @Param("international") String international);

  boolean existsByUsername(String username);

  boolean existsByUsernameIgnoreCase(String username);

  boolean existsByEmailIgnoreCase(String email);

  boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from UserAccount a where a.id = :id")
  Optional<UserAccount> lockById(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from UserAccount a where a.role = 'ADMIN' order by a.id")
  List<UserAccount> lockAdministrators();

  @Query("""
      select a from UserAccount a where ((:role = '' and a.role in ('STAFF', 'ADMIN')) or a.role = :role)
        and (:enabled is null or a.enabled = :enabled)
        and (lower(a.username) like :pattern escape '!' or lower(coalesce(a.fullName,'')) like :pattern escape '!'
          or lower(coalesce(a.email,'')) like :pattern escape '!' or coalesce(a.phone,'') like :pattern escape '!')
      """)
  Page<UserAccount> searchAdmin(@Param("role") String role, @Param("enabled") Boolean enabled,
      @Param("pattern") String pattern, Pageable pageable);

}
