package t3l.restaurant.t3l_restaurant.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.service.DatabaseUserDetailsService;
import t3l.restaurant.t3l_restaurant.service.CustomerRecovery;
import t3l.restaurant.t3l_restaurant.service.GoogleCustomerService;

@Component
public class GoogleOAuthFlow {
  private static final String GOOGLE = "google";
  private static final String LINK_INTENT = GoogleOAuthFlow.class.getName() + ".LINK_INTENT";
  private static final String RETURN_TARGET = GoogleOAuthFlow.class.getName() + ".RETURN_TARGET";
  private final InMemoryClientRegistrationRepository clients;
  private final DefaultOAuth2AuthorizationRequestResolver resolver;
  private final AuthorizationRequests requests = new AuthorizationRequests();
  private final HttpSessionOAuth2AuthorizedClientRepository authorizedClients =
      new HttpSessionOAuth2AuthorizedClientRepository();
  private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
  private final GoogleCustomerService customers;
  private final DatabaseUserDetailsService users;
  private final CustomerRecovery recovery;

  public GoogleOAuthFlow(@Value("${app.google.client-id}") String clientId,
      @Value("${app.google.client-secret}") String clientSecret, GoogleCustomerService customers,
      DatabaseUserDetailsService users, CustomerRecovery recovery) {
    this.customers = customers;
    this.users = users;
    this.recovery = recovery;
    clients = clientId.isBlank() || clientSecret.isBlank() ? null :
        new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder(GOOGLE)
            .clientId(clientId).clientSecret(clientSecret).build());
    resolver = clients == null ? null : new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
    if (resolver != null) {
      resolver.setAuthorizationRequestCustomizer(builder -> {
        OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
        builder.additionalParameters(parameters -> parameters.put("prompt", "select_account"));
      });
    }
  }

  public boolean isEnabled() { return clients != null; }
  public InMemoryClientRegistrationRepository clients() { return clients; }
  public DefaultOAuth2AuthorizationRequestResolver resolver() { return resolver; }
  public AuthorizationRequestRepository<OAuth2AuthorizationRequest> requests() { return requests; }
  public HttpSessionOAuth2AuthorizedClientRepository authorizedClients() { return authorizedClients; }

  public boolean startLink(UserAccount account, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if (!isEnabled() || account.getVerifiedPhone() == null) {
      return false;
    }
    var authorization = resolver.resolve(request, GOOGLE);
    var session = request.getSession();
    synchronized (session) {
      session.setAttribute(LINK_INTENT, new LinkIntent(account.getId(), account.getUsername(),
          authorization.getState(), Instant.now().plusSeconds(300)));
      requests.saveAuthorizationRequest(authorization, request, response);
    }
    response.sendRedirect(authorization.getAuthorizationRequestUri());
    return true;
  }

  public void onSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    var token = (OAuth2AuthenticationToken) authentication;
    authorizedClients.removeAuthorizedClient(token.getAuthorizedClientRegistrationId(), token, request, response);
    LinkIntent link = takeLinkIntent(request);
    var previousContext = contexts.loadDeferredContext(request).get();
    try {
      if (!GOOGLE.equals(token.getAuthorizedClientRegistrationId()) || !(token.getPrincipal() instanceof OidcUser user)) {
        throw new OAuth2AuthenticationException("google_unverified");
      }
      if (link != null && (link.expiresAt().isBefore(Instant.now()) || previousContext.getAuthentication() == null
          || !link.username().equals(previousContext.getAuthentication().getName()))) {
        throw new OAuth2AuthenticationException("link_expired");
      }

      String username;
      if (link == null) {
        try {
          username = signIn(user);
        } catch (OAuth2AuthenticationException exception) {
          var identity = GoogleCustomerService.GoogleProfile.from(user);
          if ("email_in_use".equals(exception.getError().getErrorCode()) && customers.canRecover(identity)) {
            SecurityContextHolder.setContext(previousContext);
            recovery.begin(request.getSession(), identity, returnTarget(request));
            response.sendRedirect(request.getContextPath() + "/login/recover");
            return;
          }
          throw exception;
        }
      } else {
        username = customers.link(link.accountId(), user);
      }
      var principal = users.loadUserByUsername(username);
      if (!principal.isEnabled() || principal.getAuthorities().stream()
          .noneMatch(authority -> authority.getAuthority().equals("ROLE_BUYER"))) {
        throw new OAuth2AuthenticationException("customer_unavailable");
      }
      var customer = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
      customer.eraseCredentials();
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(customer);
      SecurityContextHolder.setContext(context);
      contexts.saveContext(context, request, response);
      recovery.clear(request.getSession(false));
      String target = link == null ? returnTarget(request) : "/account?google=linked";
      response.sendRedirect(request.getContextPath() + (target == null ? "/" : target));
    } catch (OAuth2AuthenticationException exception) {
      SecurityContextHolder.setContext(previousContext);
      String code = switch (exception.getError().getErrorCode()) {
        case "email_in_use" -> "email-in-use";
        case "google_in_use" -> "already-linked";
        case "link_expired" -> "expired";
        default -> "failed";
      };
      redirectFailure(request, response, link != null, code);
    } catch (DataAccessException | AuthenticationException exception) {
      SecurityContextHolder.setContext(previousContext);
      redirectFailure(request, response, link != null, "failed");
    }
  }

  private String signIn(OidcUser user) {
    try {
      return customers.signIn(user);
    } catch (DataIntegrityViolationException race) {
      // A concurrent first sign-in may have just committed this Google subject.
      return customers.signIn(user);
    }
  }

  public void onFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    redirectFailure(request, response, takeLinkIntent(request) != null, "failed");
  }

  private void redirectFailure(HttpServletRequest request, HttpServletResponse response, boolean linking, String code)
      throws IOException {
    String target = linking ? null : returnTarget(request);
    var location = UriComponentsBuilder.fromPath(request.getContextPath() + (linking ? "/account" : "/login"))
        .queryParam("google", code);
    if (target != null) {
      response.sendRedirect(location.queryParam("returnTo", "{returnTo}").encode().buildAndExpand(target).toUriString());
    } else {
      response.sendRedirect(location.build().toUriString());
    }
  }

  private String returnTarget(HttpServletRequest request) {
    return (String) request.getAttribute(RETURN_TARGET);
  }

  private LinkIntent takeLinkIntent(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session == null) return null;
    synchronized (session) {
      if (session.getAttribute(LINK_INTENT) instanceof LinkIntent link
          && link.state().equals(request.getParameter("state"))) {
        session.removeAttribute(LINK_INTENT);
        return link;
      }
    }
    return null;
  }

  private record LinkIntent(Long accountId, String username, String state, Instant expiresAt) implements Serializable {}

  private static class AuthorizationRequests implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    private final HttpSessionOAuth2AuthorizationRequestRepository delegate =
        new HttpSessionOAuth2AuthorizationRequestRepository();

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
      return delegate.loadAuthorizationRequest(request);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorization, HttpServletRequest request,
        HttpServletResponse response) {
      String target = LoginReturnTarget.validate(request.getParameter("returnTo"));
      if (authorization != null && target != null) {
        // Keep the target in the session for this OAuth state, outside the request sent to Google.
        authorization = OAuth2AuthorizationRequest.from(authorization)
            .attributes(attributes -> attributes.put(RETURN_TARGET, target)).build();
      }
      delegate.saveAuthorizationRequest(authorization, request, response);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
      var authorization = delegate.removeAuthorizationRequest(request, response);
      if (authorization != null) {
        request.setAttribute(RETURN_TARGET, authorization.getAttribute(RETURN_TARGET));
      }
      return authorization;
    }
  }
}
