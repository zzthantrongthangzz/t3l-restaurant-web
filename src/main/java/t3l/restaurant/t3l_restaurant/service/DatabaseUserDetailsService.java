package t3l.restaurant.t3l_restaurant.service;

import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
  private final UserAccountRepository accounts;

  public DatabaseUserDetailsService(UserAccountRepository accounts) {
    this.accounts = accounts;
  }

  @Override
  public UserDetails loadUserByUsername(String username) {
    var account =
        accounts
            .findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy tài khoản."));
    return User.withUsername(account.getUsername())
        .password(account.getPassword())
        .roles(account.getRole())
        .disabled(!account.isEnabled())
        .build();
  }
}
