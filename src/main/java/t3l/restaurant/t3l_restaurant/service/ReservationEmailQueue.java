package t3l.restaurant.t3l_restaurant.service;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.ReservationEmail;
import t3l.restaurant.t3l_restaurant.entity.ReservationEmail.Kind;
import t3l.restaurant.t3l_restaurant.entity.ReservationEmail.Status;
import t3l.restaurant.t3l_restaurant.repository.ReservationEmailRepository;

@Service
public class ReservationEmailQueue {
  private static final List<Status> READY = List.of(Status.PENDING, Status.RETRY);
  private static final List<Status> BLOCKING = List.of(Status.PENDING, Status.RETRY, Status.SENDING);
  private static final Duration DELIVERY_DEADLINE = Duration.ofMinutes(5);
  private final ReservationEmailRepository emails;
  private final ReservationEmailSender sender;

  public ReservationEmailQueue(ReservationEmailRepository emails, ReservationEmailSender sender) {
    this.emails = emails;
    this.sender = sender;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(Reservation booking, Kind kind, Long reviewId, String reviewNote) {
    enqueueWithKey(booking, kind, reviewId == null ? "" : "-" + reviewId, reviewNote);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueueStaffReschedule(Reservation booking, Long eventId, String note) {
    enqueueWithKey(booking, Kind.RESCHEDULED, "-staff-" + eventId, note);
  }

  private void enqueueWithKey(Reservation booking, Kind kind, String keySuffix, String reviewNote) {
    // Callers create the reservation or hold its write lock before adding an event.
    String eventKey = booking.getId() + "-" + kind.name() + keySuffix;
    if (emails.existsByEventKey(eventKey)) return;
    String recipient = mailbox(booking.getGuestEmail());
    Status status = Status.PENDING;
    if (recipient == null) status = Status.SKIPPED_NO_RECIPIENT;
    else if (!sender.isConfigured()) status = Status.SKIPPED_NO_SMTP;
    String note = reviewNote == null || reviewNote.isBlank() ? null : reviewNote;
    emails.save(new ReservationEmail(booking, eventKey, kind, recipient, note, status, Instant.now()));
  }

  @Transactional(readOnly = true)
  public List<Long> readyAt(Instant now) {
    return emails.findReadyIds(READY, BLOCKING, now, PageRequest.of(0, 20));
  }

  @Transactional
  public Optional<ReservationEmail> claim(Long id, Instant now) {
    return emails.lockById(id).filter(email -> email.isDue(now)).map(email -> {
      email.claim(now);
      return email;
    });
  }

  @Transactional
  public void recordResult(Long id, ReservationEmailSender.Result result, Instant now) {
    emails.lockById(id).filter(email -> email.getStatus() == Status.SENDING).ifPresent(email -> {
      if (result.status() == Status.RETRY && email.getAttempts() < 3) {
        Duration delay = email.getAttempts() == 1 ? Duration.ofMinutes(1) : Duration.ofMinutes(5);
        email.retryAt(now.plus(delay), result.error());
      } else {
        email.finish(result.status() == Status.RETRY ? Status.FAILED : result.status(), now, result.error());
      }
    });
  }

  @Transactional
  public void markInterrupted(Instant now) {
    // SMTP may have accepted the message before a worker stopped. Do not send it again.
    emails.markInterrupted(Status.SENDING, Status.DELIVERY_UNKNOWN, now.minus(DELIVERY_DEADLINE), now);
  }

  static String mailbox(String value) {
    if (value == null || value.length() > 254 || !value.matches("^[^\\s@<>(),;:]+@[^\\s@<>(),;:]+\\.[^\\s@<>(),;:]+$")) return null;
    if (value.toLowerCase(java.util.Locale.ROOT).matches("t3l\\.886413\\+[a-z.]+@gmail\\.com")) return null;
    try {
      var address = new InternetAddress(value, true);
      return value.equals(address.getAddress()) && !address.isGroup() ? value : null;
    } catch (AddressException exception) {
      return null;
    }
  }
}
