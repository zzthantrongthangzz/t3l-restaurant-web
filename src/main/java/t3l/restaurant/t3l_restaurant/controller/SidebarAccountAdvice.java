package t3l.restaurant.t3l_restaurant.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;

@ControllerAdvice(annotations = Controller.class)
public class SidebarAccountAdvice {
  private final UserAccountRepository accounts;

  public SidebarAccountAdvice(UserAccountRepository accounts) {
    this.accounts = accounts;
  }

  @ModelAttribute("sidebarEmployee")
  EmployeeLabel sidebarEmployee(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) return null;

    String role = authentication.getAuthorities().stream()
        .map(authority -> switch (authority.getAuthority()) {
          case "ROLE_ADMIN" -> "Quản trị viên";
          case "ROLE_STAFF" -> "Nhân viên";
          default -> "";
        })
        .filter(label -> !label.isEmpty()).findFirst().orElse(null);
    if (role == null) return null;

    String username = authentication.getName();
    String name = accounts.findByUsername(username)
        .map(account -> account.getFullName())
        .filter(fullName -> !fullName.isBlank()).orElse(username);
    return new EmployeeLabel(name, role);
  }

  public record EmployeeLabel(String name, String role) {}
}
