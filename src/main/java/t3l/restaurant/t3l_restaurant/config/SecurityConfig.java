package t3l.restaurant.t3l_restaurant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.ObjectPostProcessor;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import t3l.restaurant.t3l_restaurant.security.GoogleOAuthFlow;
import t3l.restaurant.t3l_restaurant.security.AccountSessionFilter;
import t3l.restaurant.t3l_restaurant.security.JwtAuthenticationFilter;
import t3l.restaurant.t3l_restaurant.security.JwtService;
import t3l.restaurant.t3l_restaurant.service.DatabaseUserDetailsService;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
      throws Exception {
    return configuration.getAuthenticationManager();
  }

  @Bean
  AuthenticationProvider employeePasswordProvider(DatabaseUserDetailsService users, PasswordEncoder passwords) {
    var provider = new DaoAuthenticationProvider();
    provider.setPasswordEncoder(passwords);
    provider.setUserDetailsService(username -> {
      var principal = users.loadUserByUsername(username);
      if (principal.getAuthorities().stream().noneMatch(authority ->
          "ROLE_ADMIN".equals(authority.getAuthority()) || "ROLE_STAFF".equals(authority.getAuthority()))) {
        throw new UsernameNotFoundException("Không tìm thấy tài khoản nhân viên.");
      }
      return principal;
    });
    return provider;
  }

  @Bean
  @Order(1)
  SecurityFilterChain apiSecurity(
      HttpSecurity http, JwtService jwtService, DatabaseUserDetailsService users) throws Exception {
    JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService, users);
    return http.securityMatcher("/api/**")
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .anonymous(a -> a.authorities("ROLE_GUEST"))
        .authorizeHttpRequests(a ->
            a.requestMatchers("/api/auth/login")
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/api/menu")
                .permitAll()
                .requestMatchers("/api/admin/**")
                .hasRole("ADMIN")
                .requestMatchers("/api/staff/**")
                .hasAnyRole("STAFF", "ADMIN")
                .anyRequest()
                .authenticated())
        .exceptionHandling(
            e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain webSecurity(HttpSecurity http, GoogleOAuthFlow google, UserAccountRepository accounts) throws Exception {
    if (google.isEnabled()) {
      http.oauth2Login(oauth -> oauth.loginPage("/login")
          .clientRegistrationRepository(google.clients())
          .authorizedClientRepository(google.authorizedClients())
          .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(google.resolver())
              .authorizationRequestRepository(google.requests()))
          .successHandler(google::onSuccess)
          .failureHandler(google::onFailure)
          .withObjectPostProcessor(new ObjectPostProcessor<OAuth2LoginAuthenticationFilter>() {
            @Override
            public <T extends OAuth2LoginAuthenticationFilter> T postProcess(T filter) {
              // Persist the application's customer principal only after the account check.
              filter.setSecurityContextRepository(new NullSecurityContextRepository());
              return filter;
            }
          }));
    }
    return http.authorizeHttpRequests(a ->
            a.requestMatchers("/account", "/account/**", "/reservations/lookup/*/claim")
                .hasRole("BUYER")
                .requestMatchers("/login", "/oauth2/authorization/google", "/login/oauth2/code/google", "/login/sms/**",
                    "/login/recover/**", "/register", "/register/verify", "/staff/login")
                .permitAll()
                .requestMatchers("/chat/**").access(new org.springframework.security.web.access.expression.WebExpressionAuthorizationManager(
                    "isAnonymous() or hasRole('BUYER')"))
                .requestMatchers("/js/chatbox.js", "/js/preorder.js").permitAll()
                .requestMatchers(
                    "/", "/menu", "/about", "/contact", "/faq", "/reservations/**", "/css/**", "/fonts/**", "/images/**", "/error")
                .permitAll()
                .requestMatchers("/admin", "/admin/**")
                .hasRole("ADMIN")
                .requestMatchers("/staff/**")
                .hasAnyRole("STAFF", "ADMIN")
                .anyRequest()
                .authenticated())
        .exceptionHandling(handling -> handling
            .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/staff/login"), new OrRequestMatcher(
                new AntPathRequestMatcher("/staff/**"), new AntPathRequestMatcher("/admin/**")))
            .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"), AnyRequestMatcher.INSTANCE))
        .formLogin(form -> form.loginPage("/login").failureUrl("/staff/login?error")
            .successHandler((request, response, authentication) -> {
              boolean admin = authentication.getAuthorities().stream()
                  .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
              response.sendRedirect(request.getContextPath() + (admin ? "/admin/dashboard" : "/staff/dashboard"));
            }).permitAll())
        .addFilterBefore(new AccountSessionFilter(accounts), AnonymousAuthenticationFilter.class)
        .logout(l -> l.logoutSuccessUrl("/"))
        .build();
  }
}
