package t3l.restaurant.t3l_restaurant.service;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class ReservationEmailDispatcher {
  private final ReservationEmailQueue queue;
  private final ReservationEmailSender sender;
  private final boolean workerEnabled;

  public ReservationEmailDispatcher(ReservationEmailQueue queue, ReservationEmailSender sender,
      @Value("${app.reservation-mail.worker-enabled:true}") boolean workerEnabled) {
    this.queue = queue;
    this.sender = sender;
    this.workerEnabled = workerEnabled;
  }

  @Scheduled(initialDelayString = "PT10S", fixedDelayString = "PT10S")
  void poll() {
    if (workerEnabled) dispatchReady();
  }

  void dispatchReady() {
    if (!sender.isConfigured()) return;
    Instant now = Instant.now();
    queue.markInterrupted(now);
    for (Long id : queue.readyAt(now)) {
      queue.claim(id, Instant.now()).ifPresent(email ->
          queue.recordResult(id, sender.deliver(email), Instant.now()));
    }
  }
}
