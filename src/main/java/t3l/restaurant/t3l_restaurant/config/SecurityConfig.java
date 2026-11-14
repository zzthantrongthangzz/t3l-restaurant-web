package t3l.restaurant.t3l_restaurant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import t3l.restaurant.t3l_restaurant.security.JwtAuthenticationFilter;
import t3l.restaurant.t3l_restaurant.security.JwtService;
import t3l.restaurant.t3l_restaurant.service.DatabaseUserDetailsService;

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
  SecurityFilterChain webSecurity(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(a ->
            a.requestMatchers(
                    "/", "/menu", "/faq", "/reservations/**", "/css/**", "/images/**", "/error")
                .permitAll()
                .requestMatchers("/admin/**")
                .hasRole("ADMIN")
                .requestMatchers("/staff/**")
                .hasAnyRole("STAFF", "ADMIN")
                .anyRequest()
                .authenticated())
        .formLogin(f ->
            f.loginPage("/login")
                .successHandler((request, response, authentication) -> {
                  boolean employee =
                      authentication.getAuthorities().stream()
                          .anyMatch(authority ->
                              authority.getAuthority().equals("ROLE_ADMIN")
                                  || authority.getAuthority().equals("ROLE_STAFF"));
                  response.sendRedirect(
                      request.getContextPath() + (employee ? "/staff/reservations" : "/"));
                })
                .permitAll())
        .logout(l -> l.logoutSuccessUrl("/"))
        .build();
  }
}
