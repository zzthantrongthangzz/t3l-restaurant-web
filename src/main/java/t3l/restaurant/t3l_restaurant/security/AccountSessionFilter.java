package t3l.restaurant.t3l_restaurant.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;

@RequiredArgsConstructor
public class AccountSessionFilter extends OncePerRequestFilter {
  private final UserAccountRepository accounts;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getServletPath();
    return path.startsWith("/css/") || path.startsWith("/fonts/") || path.startsWith("/js/") || path.startsWith("/images/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof AccountPrincipal principal) {
      boolean current = accounts.findByUsername(principal.getUsername()).filter(account -> account.isEnabled()
          && account.getAuthVersion() == principal.getVersion()
          && principal.getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_" + account.getRole())))
          .isPresent();
      if (!current) new SecurityContextLogoutHandler().logout(request, response, auth);
    }
    chain.doFilter(request, response);
  }
}
