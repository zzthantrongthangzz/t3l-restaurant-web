package t3l.restaurant.t3l_restaurant.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import t3l.restaurant.t3l_restaurant.entity.ContactMessage;

public interface ContactMessageRepository extends JpaRepository<ContactMessage, Long> {
  Page<ContactMessage> findByStatus(String status, Pageable pageable);
}
