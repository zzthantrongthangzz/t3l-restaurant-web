package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "app_users", uniqueConstraints = {
    @UniqueConstraint(name = "uk_app_users_email", columnNames = "email"),
    @UniqueConstraint(name = "uk_app_users_verified_phone", columnNames = "verified_phone")
})
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class UserAccount {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 80)
  private String username;

  @Column(name = "full_name", length = 120)
  private String fullName;

  @Column(length = 254)
  private String email;

  @Column(name = "email_verified_at")
  private Instant emailVerifiedAt;

  @Column(length = 30)
  private String phone;

  @Column(name = "verified_phone", length = 16)
  private String verifiedPhone;

  @Column(nullable = false, length = 100)
  private String password;

  @Column(nullable = false, length = 30)
  private String role;

  @Column(nullable = false)
  private boolean enabled = true;

  @Column(name = "auth_version", nullable = false)
  @org.hibernate.annotations.ColumnDefault("0")
  private long authVersion;

  @Column(name = "archived_at")
  private LocalDateTime archivedAt;

  public UserAccount(String username, String password, String role) {
    this.username = username;
    this.password = password;
    this.role = role;
  }

  public static UserAccount customer(String fullName, String email, String phone, String username, String encodedPassword) {
    UserAccount account = new UserAccount(username, encodedPassword, "BUYER");
    account.fullName = fullName;
    account.email = email;
    account.phone = phone;
    return account;
  }

  public void manage(String role, boolean enabled, String encodedPassword) {
    if (!this.role.equals(role) || this.enabled != enabled || encodedPassword != null) authVersion++;
    this.role = role;
    this.enabled = enabled;
    if (encodedPassword != null) this.password = encodedPassword;
  }

  public void archive(LocalDateTime now) {
    manage(role, false, null);
    archivedAt = now;
  }

  public void restore() {
    archivedAt = null;
  }

  public void updateCustomerContact(String fullName, String email, String phone) {
    this.fullName = fullName;
    if (!Objects.equals(this.email, email)) emailVerifiedAt = null;
    this.email = email;
    if (verifiedPhone != null && !phoneDigits(verifiedPhone).equals(phoneDigits(phone))) verifiedPhone = null;
    this.phone = phone;
  }

  private String phoneDigits(String value) {
    return value == null ? "" : value.replaceAll("\\D", "");
  }

  public void verifyPhone(String phone) {
    this.verifiedPhone = phone;
    this.phone = phone;
  }

  public void verifyEmail(String email, Instant verifiedAt) {
    this.email = email;
    this.emailVerifiedAt = verifiedAt;
  }

  public void invalidateAuthentication() {
    authVersion++;
  }
}
