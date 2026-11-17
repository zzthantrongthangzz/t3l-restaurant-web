package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "app_users", uniqueConstraints = @UniqueConstraint(name = "uk_app_users_email", columnNames = "email"))
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

  @Column(length = 30)
  private String phone;

  @Column(nullable = false, length = 100)
  private String password;

  @Column(nullable = false, length = 30)
  private String role;

  @Column(nullable = false)
  private boolean enabled = true;

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

  public void setDemoCredentials(String encodedPassword, String role) {
    this.password = encodedPassword;
    this.role = role;
    this.enabled = true;
  }
}
