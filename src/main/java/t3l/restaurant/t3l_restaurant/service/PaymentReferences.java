package t3l.restaurant.t3l_restaurant.service;

import jakarta.persistence.EntityManager;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import t3l.restaurant.t3l_restaurant.entity.PaymentReference;

@Component
@RequiredArgsConstructor
public class PaymentReferences {
  private final EntityManager entities;

  public String normalize(String value, int minLength) {
    String reference = value == null ? "" : value.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    if (reference.length() < minLength || reference.length() > 80) {
      throw new BookingException(null, "Mã giao dịch phải có từ " + minLength + " đến 80 ký tự.");
    }
    return reference;
  }

  public void requireUnused(String reference) {
    if (entities.find(PaymentReference.class, reference) != null) throw duplicate();
  }

  public void claim(String reference, String purpose, Long id) {
    requireUnused(reference);
    try {
      entities.persist(new PaymentReference(reference, purpose, id));
      entities.flush();
    } catch (jakarta.persistence.PersistenceException | DataIntegrityViolationException exception) {
      throw duplicate();
    }
  }

  private BookingException duplicate() {
    return new BookingException(null, "Mã giao dịch đã được ghi nhận. Vui lòng đối chiếu lại.");
  }
}
