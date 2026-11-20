package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import t3l.restaurant.t3l_restaurant.dto.CustomerProfileForm;
import t3l.restaurant.t3l_restaurant.dto.InvoiceCompanyForm;

@Entity
@Table(name = "customer_profiles", uniqueConstraints =
    @UniqueConstraint(name = "uk_customer_profiles_google_subject", columnNames = "google_subject"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class CustomerProfile {
  @Id
  private Long id;

  @MapsId
  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_customer_profiles_user"))
  private UserAccount account;

  @Column(name = "family_name", length = 120)
  private String familyName;

  @Column(name = "given_name", length = 120)
  private String givenName;

  @Column(name = "dietary_preferences", length = 500)
  private String dietaryPreferences;

  @Column(name = "reservation_email", nullable = false)
  private boolean reservationEmail;

  @Column(name = "order_email", nullable = false)
  private boolean orderEmail;

  @Column(name = "booking_notifications", nullable = false, columnDefinition = "boolean default false")
  private boolean bookingNotifications;

  @Column(name = "marketing_opt_in", nullable = false, columnDefinition = "boolean default false")
  private boolean marketingOptIn;

  @Column(name = "google_subject", length = 255)
  private String googleSubject;

  @Column(name = "google_name", length = 120)
  private String googleName;

  @Column(name = "google_email", length = 254)
  private String googleEmail;

  @Column(name = "avatar_url", length = 2048)
  private String avatarUrl;

  @Column(name = "company_name", length = 180)
  private String companyName;

  @Column(name = "company_tax_code", length = 30)
  private String companyTaxCode;

  @Column(name = "company_address", length = 300)
  private String companyAddress;

  @Column(name = "invoice_email", length = 254)
  private String invoiceEmail;

  public CustomerProfile(UserAccount account) {
    this.account = account;
  }

  public void updateProfile(CustomerProfileForm form) {
    familyName = form.getFamilyName().strip();
    givenName = form.getGivenName().strip();
    String fullName = (familyName + " " + givenName).strip();
    account.updateCustomerContact(fullName, form.getEmail() == null || form.getEmail().isBlank()
        ? null : form.getEmail().toLowerCase(java.util.Locale.ROOT),
        form.getPhone() == null || form.getPhone().isBlank() ? null : form.getPhone().strip());
    dietaryPreferences = form.getDietaryPreferences();
    bookingNotifications = form.isBookingNotifications();
    marketingOptIn = form.isMarketingOptIn();
  }

  public void updateGoogle(String subject, String name, String email, String picture) {
    googleSubject = subject;
    googleName = name;
    googleEmail = email;
    avatarUrl = picture;
  }

  public void fillGoogleNames(String family, String given) {
    if (givenName == null && given != null && !given.isBlank()) {
      familyName = family == null ? "" : family;
      givenName = given;
    }
  }

  public void updateCompany(InvoiceCompanyForm form) {
    companyName = form.getCompanyName().strip();
    companyTaxCode = form.getTaxCode().strip();
    companyAddress = form.getCompanyAddress().strip();
    invoiceEmail = form.getInvoiceEmail().strip();
  }
}
