package t3l.restaurant.t3l_restaurant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionTemplate;
import t3l.restaurant.t3l_restaurant.dto.ReservationCancellationForm;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.dto.ReservationUpdateForm;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.ReservationEmail;
import t3l.restaurant.t3l_restaurant.entity.ReservationEmail.Kind;
import t3l.restaurant.t3l_restaurant.entity.ReservationEmail.Status;
import t3l.restaurant.t3l_restaurant.repository.ReservationChangeRequestRepository;
import t3l.restaurant.t3l_restaurant.repository.ReservationEmailRepository;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:bookingEmailTest;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.sql.init.mode=never", "spring.mail.username=t3l-qa@example.test", "spring.mail.password=test-only",
    "app.reservation-mail.public-base-url=https://restaurant.example.test/t3l/",
    "app.reservation-mail.worker-enabled=false",
    "app.jwt.secret=Y3VzdG9tZXItYWNjb3VudHMtdGVzdC1rZXktdGhpcy1pcy1mb3ItdGVzdHMtb25seQ=="
})
class ReservationEmailIntegrationTest {
  private static final LocalDate DATE = LocalDate.of(2099, 3, 14);
  private static final LocalTime TIME = LocalTime.of(18, 0);
  private static final String RECIPIENT = "guest@example.test";
  @Autowired ReservationService bookings;
  @Autowired ReservationManagementService management;
  @Autowired ReservationRepository reservations;
  @Autowired ReservationChangeRequestRepository requests;
  @Autowired ReservationEmailRepository emails;
  @Autowired ReservationEmailQueue queue;
  @Autowired ReservationEmailDispatcher dispatcher;
  @Autowired TransactionTemplate transactions;
  @Autowired JdbcTemplate jdbc;
  @MockBean JavaMailSender mail;
  @SpyBean ReservationEmailSender sender;

  @BeforeEach
  void prepare() {
    requests.deleteAllInBatch();
    reservations.deleteAllInBatch();
    if (jdbc.queryForObject("select count(*) from restaurant_tables", Integer.class) == 0) {
      jdbc.update("insert into restaurant_tables(code,table_type,capacity,active) values (?,?,?,?)",
          "EMAIL-QA", "Phòng VIP", 6, true);
    }
    when(mail.createMimeMessage()).thenAnswer(ignored -> new MimeMessage(Session.getInstance(new Properties())));
  }

  @Test
  void actualBookingTransitionsCaptureTheCorrectEmailOnce() {
    var booking = book(RECIPIENT, DATE);
    bookings.updateStatus(booking.getId(), "CONFIRMED");
    bookings.updateStatus(booking.getId(), "CONFIRMED");
    var change = new ReservationUpdateForm();
    change.setDate(DATE.plusDays(1));
    change.setTime(LocalTime.of(19, 30));
    change.setGuests(3);
    var changeRequest = management.requestChange(booking.getCode(), change);
    management.review(changeRequest.getId(), "APPROVE", null, "staff");
    assertThrows(BookingException.class, () -> management.review(changeRequest.getId(), "APPROVE", null, "staff"));

    var declinedCancellation = management.requestCancellation(booking.getCode(), new ReservationCancellationForm());
    management.review(declinedCancellation.getId(), "REJECT", "Vui lòng liên hệ nhà hàng", "staff");
    var cancellation = management.requestCancellation(booking.getCode(), new ReservationCancellationForm());
    management.review(cancellation.getId(), "APPROVE", null, "staff");
    bookings.updateStatus(booking.getId(), "CANCELLED");

    var declined = book(RECIPIENT, DATE.plusDays(2));
    bookings.updateStatus(declined.getId(), "CANCELLED");
    var cancelled = book(RECIPIENT, DATE.plusDays(3));
    bookings.updateStatus(cancelled.getId(), "CONFIRMED");
    bookings.updateStatus(cancelled.getId(), "CANCELLED");

    var events = events();
    assertEquals(List.of(Kind.RECEIVED, Kind.CONFIRMED, Kind.RESCHEDULED, Kind.CANCELLATION_DECLINED,
        Kind.CANCELLATION_APPROVED, Kind.RECEIVED, Kind.REJECTED, Kind.RECEIVED, Kind.CONFIRMED, Kind.CANCELLED),
        events.stream().map(ReservationEmail::getKind).toList());
    assertEquals(events.size(), events.stream().map(ReservationEmail::getEventKey).distinct().count());
    var rescheduled = events.get(2);
    assertEquals(DATE.plusDays(1).atTime(19, 30), rescheduled.getStartsAt());
    assertEquals(3, rescheduled.getGuestCount());
    assertEquals("Phòng VIP", rescheduled.getArea());
    assertEquals("Đã xác nhận", rescheduled.getBookingStatus());
    assertEquals("Vui lòng liên hệ nhà hàng", events.get(3).getReviewNote());
    assertEquals("Đã hủy", events.get(4).getBookingStatus());
    verify(mail, never()).send(any(MimeMessage.class));
  }

  @Test
  void deliversDistinctReceiptAndConfirmationAsMultipartMessagesAfterCommit() throws Exception {
    var booking = book(RECIPIENT, DATE);
    bookings.updateStatus(booking.getId(), "CONFIRMED");
    verify(mail, never()).send(any(MimeMessage.class));

    dispatcher.dispatchReady();
    assertEquals(List.of(Status.SENT, Status.PENDING), events().stream().map(ReservationEmail::getStatus).toList());
    dispatcher.dispatchReady();
    dispatcher.dispatchReady();
    var captor = ArgumentCaptor.forClass(MimeMessage.class);
    verify(mail, times(2)).send(captor.capture());
    var receipt = captor.getAllValues().getFirst();
    var confirmation = captor.getAllValues().getLast();
    assertTrue(receipt.getSubject().contains("Đã tiếp nhận yêu cầu"));
    assertTrue(confirmation.getSubject().contains("Xác nhận đặt bàn chính thức"));
    assertEquals(RECIPIENT, receipt.getAllRecipients()[0].toString());
    assertNotEquals(receipt.getMessageID(), confirmation.getMessageID());
    String html = content(receipt, "text/html");
    assertNotNull(html);
    assertTrue(html.contains("Chờ xác nhận"));
    assertTrue(html.contains("https://restaurant.example.test/t3l/reservations/lookup?code=" + booking.getCode()));
    assertTrue(html.contains("14/03/2099"));
    assertTrue(html.contains("18:00"));
    assertTrue(html.contains("Phòng VIP"));
    assertTrue(html.contains("&lt;img"));
    assertFalse(html.contains("<img src="));
    assertTrue(content(confirmation, "text/plain").contains("Đã xác nhận"));
  }

  @Test
  void rolledBackBookingsDoNotLeaveDeliverableEmails() {
    transactions.executeWithoutResult(transaction -> {
      book(RECIPIENT, DATE);
      transaction.setRollbackOnly();
    });
    assertEquals(0, reservations.count());
    assertEquals(0, emails.count());
    dispatcher.dispatchReady();
    verify(mail, never()).send(any(MimeMessage.class));
  }

  @Test
  void missingInvalidAndUnconfiguredDestinationsAreSkippedWithoutClaimingDelivery() {
    book("", DATE);
    var legacy = reservations.saveAndFlush(new Reservation("T3L-TESTBAD123", "Khách cũ", "0901234567",
        "one@example.test,two@example.test", null, DATE.plusDays(1), TIME, 2, "", true));
    bookings.updateStatus(legacy.getId(), "CANCELLED");
    doReturn(false).when(sender).isConfigured();
    book(RECIPIENT, DATE.plusDays(2));

    assertEquals(List.of(Status.SKIPPED_NO_RECIPIENT, Status.SKIPPED_NO_RECIPIENT, Status.SKIPPED_NO_SMTP),
        events().stream().map(ReservationEmail::getStatus).toList());
    assertTrue(events().stream().allMatch(event -> event.getAttempts() == 0));
    dispatcher.dispatchReady();
    verify(mail, never()).send(any(MimeMessage.class));
  }

  @Test
  void connectionFailureRetriesAndKeepsTheBookingAndMessageIdentity() throws Exception {
    doThrow(new MailSendException("Connection failed", new ConnectException("Mock transport")))
        .doNothing().when(mail).send(any(MimeMessage.class));
    var booking = book(RECIPIENT, DATE);
    bookings.updateStatus(booking.getId(), "CONFIRMED");
    dispatcher.dispatchReady();
    var initial = events().getFirst();
    assertEquals(Status.RETRY, initial.getStatus());
    assertEquals(1, initial.getAttempts());
    assertEquals("SMTP_CONNECT_FAILED", initial.getLastError());
    assertTrue(initial.getNextAttemptAt().isAfter(Instant.now()));
    assertEquals("CONFIRMED", bookings.findByCode(booking.getCode()).getStatus());

    dispatcher.dispatchReady();
    verify(mail, times(1)).send(any(MimeMessage.class));
    makeDue();
    dispatcher.dispatchReady();
    var sent = ArgumentCaptor.forClass(MimeMessage.class);
    verify(mail, times(2)).send(sent.capture());
    assertEquals(sent.getAllValues().getFirst().getMessageID(), sent.getAllValues().getLast().getMessageID());
    assertEquals(Status.SENT, events().getFirst().getStatus());
    assertEquals(2, events().getFirst().getAttempts());
    assertEquals(Status.PENDING, events().getLast().getStatus());
  }

  @Test
  void transientFailuresAreCappedAndUnknownDeliveriesAreNeverAutomaticallyRepeated() {
    doThrow(new MailSendException("Connection failed", new ConnectException("Mock transport")))
        .when(mail).send(any(MimeMessage.class));
    book(RECIPIENT, DATE);
    for (int i = 0; i < 3; i++) {
      makeDue();
      dispatcher.dispatchReady();
    }
    dispatcher.dispatchReady();
    assertEquals(Status.FAILED, events().getFirst().getStatus());
    assertEquals(3, events().getFirst().getAttempts());
    verify(mail, times(3)).send(any(MimeMessage.class));

    doThrow(new MailSendException("Delivery result missing", new SocketTimeoutException("Mock timeout")))
        .when(mail).send(any(MimeMessage.class));
    book(RECIPIENT, DATE.plusDays(1));
    dispatcher.dispatchReady();
    dispatcher.dispatchReady();
    assertEquals(Status.DELIVERY_UNKNOWN, events().getLast().getStatus());
    assertEquals(1, events().getLast().getAttempts());
    verify(mail, times(4)).send(any(MimeMessage.class));
  }

  @Test
  void explicitSmtpRejectionsRetryOnlyWhenTemporary() {
    doThrow(
        new MailSendException("Mock temporary rejection",
            new SMTPSendFailedException("DATA", 451, "Temporary error", null, null, null, null)),
        new MailSendException("Mock permanent rejection",
            new SMTPSendFailedException("DATA", 550, "Permanent error", null, null, null, null)))
        .when(mail).send(any(MimeMessage.class));
    book(RECIPIENT, DATE);
    dispatcher.dispatchReady();
    assertEquals(Status.RETRY, events().getFirst().getStatus());
    assertEquals("SMTP_451", events().getFirst().getLastError());
    makeDue();
    dispatcher.dispatchReady();
    assertEquals(Status.FAILED, events().getFirst().getStatus());
    assertEquals("SMTP_550", events().getFirst().getLastError());
    dispatcher.dispatchReady();
    verify(mail, times(2)).send(any(MimeMessage.class));
  }

  @Test
  void racingWorkersClaimOneDeliveryAndInterruptedClaimsAreNeverRetried() throws Exception {
    book(RECIPIENT, DATE);
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first = workers.submit(() -> { start.await(); dispatcher.dispatchReady(); return null; });
      var second = workers.submit(() -> { start.await(); dispatcher.dispatchReady(); return null; });
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }
    verify(mail, times(1)).send(any(MimeMessage.class));
    assertEquals(Status.SENT, events().getFirst().getStatus());

    book(RECIPIENT, DATE.plusDays(1));
    Instant now = Instant.now().plusSeconds(1);
    queue.claim(events().getLast().getId(), now).orElseThrow();
    queue.markInterrupted(now.plusSeconds(301));
    dispatcher.dispatchReady();
    assertEquals(Status.DELIVERY_UNKNOWN, events().getLast().getStatus());
    verify(mail, times(1)).send(any(MimeMessage.class));
  }

  private void makeDue() {
    jdbc.update("update reservation_emails set next_attempt_at=created_at where status='RETRY'");
  }

  private List<ReservationEmail> events() {
    return emails.findAll(org.springframework.data.domain.Sort.by("id"));
  }

  private Reservation book(String email, LocalDate date) {
    var form = new ReservationForm();
    form.setGuestName("Khách <img src=x>");
    form.setGuestPhone("0901234567");
    form.setGuestEmail(email);
    form.setDate(date);
    form.setTime(TIME);
    form.setGuests(2);
    form.setConsentAccepted(true);
    return bookings.create(form);
  }

  private String content(Part part, String mediaType) throws Exception {
    if (part.isMimeType(mediaType)) return (String) part.getContent();
    if (part.getContent() instanceof Multipart multipart) {
      for (int i = 0; i < multipart.getCount(); i++) {
        String value = content(multipart.getBodyPart(i), mediaType);
        if (value != null) return value;
      }
    }
    return null;
  }
}
