package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import t3l.restaurant.t3l_restaurant.security.GoogleOAuthFlow;
import t3l.restaurant.t3l_restaurant.security.LoginReturnTarget;
import t3l.restaurant.t3l_restaurant.service.CustomerAccountService;

@ControllerAdvice(assignableTypes = {WebController.class, ReservationLookupController.class,
    CustomerLoginController.class, ContactController.class, CustomerAccountController.class, PreorderController.class})
public class CustomerHeaderAdvice {
  private final CustomerAccountService customers;
  private final GoogleOAuthFlow google;

  public CustomerHeaderAdvice(CustomerAccountService customers, GoogleOAuthFlow google) {
    this.customers = customers;
    this.google = google;
  }

  @ModelAttribute("customerBadge")
  CustomerBadge customerBadge(Authentication authentication) {
    var account = customers.signedInCustomer(authentication);
    if (account == null) return null;
    var profile = customers.profile(account);
    String name = account.getFullName();
    if (name == null || name.isBlank()) name = "Khách T3L";
    String initial = name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
    return new CustomerBadge(name, initial, profile.getAvatarUrl());
  }

  @ModelAttribute("googleEnabled")
  boolean googleEnabled() {
    return google.isEnabled();
  }

  @ModelAttribute("loginReturnTo")
  String loginReturnTo(HttpServletRequest request) {
    if (!"GET".equals(request.getMethod())) return null;
    String path = request.getRequestURI().substring(request.getContextPath().length());
    String query = request.getQueryString();
    return LoginReturnTarget.validate(query == null ? path : path + "?" + query);
  }

  public record CustomerBadge(String name, String initial, String avatar) {}
}
