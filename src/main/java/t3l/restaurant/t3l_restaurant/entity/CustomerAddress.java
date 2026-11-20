package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import t3l.restaurant.t3l_restaurant.dto.CustomerAddressForm;

@Entity
@Table(name = "customer_addresses", indexes = @Index(name = "ix_customer_addresses_user", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class CustomerAddress {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_customer_addresses_user"))
  private UserAccount account;

  @Column(name = "recipient_name", nullable = false, length = 120)
  private String recipientName;

  @Column(nullable = false, length = 30)
  private String phone;

  @Column(name = "street_address", nullable = false, length = 300)
  private String streetAddress;

  @Column(name = "is_default", nullable = false)
  private boolean defaultAddress;

  public CustomerAddress(UserAccount account) {
    this.account = account;
  }

  public void update(CustomerAddressForm form) {
    recipientName = form.getRecipientName().strip();
    phone = form.getPhone().strip();
    streetAddress = form.getStreetAddress().strip();
  }

  public void setDefaultAddress(boolean defaultAddress) {
    this.defaultAddress = defaultAddress;
  }
}
