package t3l.restaurant.t3l_restaurant.service;

import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;
import t3l.restaurant.t3l_restaurant.security.AccountPrincipal;

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
    return new AccountPrincipal(account);
  }
}
