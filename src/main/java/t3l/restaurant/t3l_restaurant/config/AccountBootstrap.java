package t3l.restaurant.t3l_restaurant.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;

@Configuration
public class AccountBootstrap {
  @Bean
  CommandLineRunner createDemoAccounts(
      UserAccountRepository accounts,
      PasswordEncoder encoder,
      @Value("${app.admin.username}") String admin,
      @Value("${app.admin.password}") String adminPassword,
      @Value("${app.staff.username}") String staff,
      @Value("${app.staff.password}") String staffPassword) {
    return args -> {
      ensureDemoAccount(accounts, encoder, admin, adminPassword, "ADMIN");
      ensureDemoAccount(accounts, encoder, staff, staffPassword, "STAFF");
    };
  }

  private void ensureDemoAccount(
      UserAccountRepository accounts,
      PasswordEncoder encoder,
      String username,
      String password,
      String role) {
    if (!accounts.existsByUsernameIgnoreCase(username)) {
      accounts.save(new UserAccount(username, encoder.encode(password), role));
    }
  }
}
