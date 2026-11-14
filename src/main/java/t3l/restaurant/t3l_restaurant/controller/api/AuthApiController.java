package t3l.restaurant.t3l_restaurant.controller.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import t3l.restaurant.t3l_restaurant.security.JwtService;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthApiController {
  private final AuthenticationManager authenticationManager;
  private final JwtService jwtService;

  public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

  public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}

  @PostMapping("/login")
  public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
    try {
      var auth =
          authenticationManager.authenticate(
              new UsernamePasswordAuthenticationToken(request.username(), request.password()));
      String token = jwtService.generateToken((UserDetails) auth.getPrincipal());
      return ResponseEntity.ok(
          new TokenResponse(token, "Bearer", jwtService.getExpirationSeconds()));
    } catch (AuthenticationException exception) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("error", "Tên đăng nhập hoặc mật khẩu không đúng."));
    }
  }
}
