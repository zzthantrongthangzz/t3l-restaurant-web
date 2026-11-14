package t3l.restaurant.t3l_restaurant.controller.api;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import t3l.restaurant.t3l_restaurant.service.ReservationService;

@RestController
@RequestMapping("/api/staff/reservations")
@RequiredArgsConstructor
public class StaffReservationApiController {
  private final ReservationService reservations;

  public record ReservationSummary(
      String code,
      String guestName,
      java.time.LocalDate date,
      java.time.LocalTime time,
      int guests,
      String status) {}

  @GetMapping
  public java.util.List<ReservationSummary> list() {
    return reservations.findRecent().stream()
        .map(r ->
            new ReservationSummary(
                r.getCode(),
                r.getGuestName(),
                r.getReservationDate(),
                r.getReservationTime(),
                r.getGuestCount(),
                r.getStatus()))
        .toList();
  }
}
