package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "app_users")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class UserAccount {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 80)
  private String username;

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

  public void setDemoCredentials(String encodedPassword, String role) {
    this.password = encodedPassword;
    this.role = role;
    this.enabled = true;
  }
}
