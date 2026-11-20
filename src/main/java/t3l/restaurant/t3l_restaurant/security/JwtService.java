package t3l.restaurant.t3l_restaurant.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
  private final SecretKey key;
  private final long expirationMs;

  public JwtService(
      @Value("${app.jwt.secret}") String secret,
      @Value("${app.jwt.expiration-ms}") long expirationMs) {
    this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    this.expirationMs = expirationMs;
  }

  public String generateToken(UserDetails user) {
    Instant now = Instant.now();
    List<String> roles =
        user.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    return Jwts.builder()
        .subject(user.getUsername())
        .claim("roles", roles)
        .claim("version", user instanceof AccountPrincipal principal ? principal.getVersion() : 0)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusMillis(expirationMs)))
        .signWith(key)
        .compact();
  }

  public boolean isValid(String token, UserDetails user) {
    Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    long current = user instanceof AccountPrincipal principal ? principal.getVersion() : 0;
    long issued = claims.get("version") instanceof Number number ? number.longValue() : 0;
    var roles = user.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    return user.isEnabled() && claims.getSubject().equals(user.getUsername()) && issued == current
        && roles.equals(claims.get("roles")) && claims.getExpiration().after(new Date());
  }

  public String extractUsername(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject();
  }

  public long getExpirationSeconds() {
    return expirationMs / 1000;
  }
}
