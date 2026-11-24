package t3l.restaurant.t3l_restaurant.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.deposits.expiry-enabled", matchIfMissing = true)
public class DepositExpiry {
  private final ReservationRepository reservations;
  private final ReservationService bookings;
  private final ReservationSchedule schedule;

  @Scheduled(initialDelay = 30000, fixedDelay = 30000)
  public void releaseOverdueTables() {
    var overdue = reservations.expiredDepositIds(LocalDateTime.now(schedule.getClock()), PageRequest.of(0, 100));
    for (Long id : overdue) {
      try {
        bookings.releaseExpiredDeposit(id);
      } catch (RuntimeException exception) {
        log.warn("Could not release overdue reservation {}", id, exception);
      }
    }
  }
}
