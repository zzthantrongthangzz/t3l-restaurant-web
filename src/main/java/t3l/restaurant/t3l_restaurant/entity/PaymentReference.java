package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_reference_claims")
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class PaymentReference {
  @Id
  @Column(name = "reference_code", length = 80)
  private String reference;

  @Column(nullable = false, length = 16)
  private String purpose;

  @Column(name = "record_id", nullable = false)
  private Long recordId;

  public PaymentReference(String reference, String purpose, Long recordId) {
    this.reference = reference;
    this.purpose = purpose;
    this.recordId = recordId;
  }
}
