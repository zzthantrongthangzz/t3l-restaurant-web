package t3l.restaurant.t3l_restaurant.security;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;

public class AccountPrincipal extends User {
  private final long version;

  public AccountPrincipal(UserAccount account) {
    super(account.getUsername(), account.getPassword(), account.isEnabled(), true, true, true,
        List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole())));
    this.version = account.getAuthVersion();
  }

  public long getVersion() { return version; }
}
