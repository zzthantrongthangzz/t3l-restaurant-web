package t3l.restaurant.t3l_restaurant.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Component;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.service.DatabaseUserDetailsService;

@Component
public class CustomerSession {
  private final DatabaseUserDetailsService users;
  private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

  public CustomerSession(DatabaseUserDetailsService users) {
    this.users = users;
  }

  public void signIn(HttpServletRequest request, HttpServletResponse response, UserAccount account) {
    var principal = users.loadUserByUsername(account.getUsername());
    if (!principal.isEnabled() || principal.getAuthorities().stream()
        .noneMatch(authority -> authority.getAuthority().equals("ROLE_BUYER"))) {
      throw new org.springframework.security.authentication.DisabledException("Customer account unavailable");
    }
    if (request.getSession(false) != null) request.changeSessionId();
    new HttpSessionCsrfTokenRepository().saveToken(null, request, response);
    var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    authentication.eraseCredentials();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
  }
}
