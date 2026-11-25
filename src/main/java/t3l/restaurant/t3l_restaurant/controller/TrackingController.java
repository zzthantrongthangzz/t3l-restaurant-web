package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import t3l.restaurant.t3l_restaurant.dto.TrackingInput;
import t3l.restaurant.t3l_restaurant.service.TrackingService;

@RestController
@RequestMapping("/tracking")
@RequiredArgsConstructor
public class TrackingController {
  private final TrackingService tracking;

  @GetMapping("/preferences")
  public ResponseEntity<?> preferences(HttpServletRequest request, Authentication auth, CsrfToken csrf) {
    String choice = tracking.preference(request, auth);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body("disabled".equals(choice)
        ? Map.of("choice", choice) : Map.of("choice", choice, "header", csrf.getHeaderName(), "token", csrf.getToken()));
  }

  @PostMapping(path = "/preferences", consumes = "application/json")
  public ResponseEntity<Void> preferences(@Valid @RequestBody TrackingInput.Consent input,
      HttpServletRequest request, Authentication auth) {
    if (!tracking.eligible(auth)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, tracking.consent(request, input.allowed()).toString()).build();
  }

  @PostMapping(path = "/events", consumes = "application/json")
  public ResponseEntity<Void> events(@Valid @RequestBody TrackingInput.Batch input,
      HttpServletRequest request, Authentication auth) {
    if (!"allowed".equals(tracking.preference(request, auth))) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    return tracking.batch(request, auth, input) ? ResponseEntity.noContent().build()
        : ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "60").build();
  }
}
