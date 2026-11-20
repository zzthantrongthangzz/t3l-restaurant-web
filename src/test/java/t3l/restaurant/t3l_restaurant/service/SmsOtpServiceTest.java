package t3l.restaurant.t3l_restaurant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SmsOtpServiceTest {
  @Test
  void otpExpiresAfterFiveMinutesEvenWithTheCorrectCode() {
    var sender = mock(EsmsOtpSender.class);
    var request = new MockHttpServletRequest();
    var current = new AtomicReference<>(Instant.parse("2026-01-01T10:00:00Z"));
    var clock = mock(Clock.class);
    when(clock.instant()).thenAnswer(call -> current.get());
    when(sender.local(request)).thenReturn(true);
    when(sender.localPhone("0900000000")).thenReturn(true);
    var service = new SmsOtpService(sender, mock(SmsOtpRateLimiter.class), clock);

    var issue = service.issue(request, null, "0900000000", "/menu");
    current.updateAndGet(time -> time.plusSeconds(299));
    assertEquals("0900000000", service.pendingPhone(request.getSession(), null));
    current.updateAndGet(time -> time.plusSeconds(1));
    assertThrows(SmsOtpService.OtpException.class, () -> service.verify(request, null, issue.localCode()));
    assertNull(service.pendingPhone(request.getSession(), null));
  }

  @Test
  void failedResendLeavesThePreviousCodeUsable() {
    var sender = mock(EsmsOtpSender.class);
    when(sender.live()).thenReturn(true);
    var sent = new AtomicReference<String>();
    when(sender.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
      if (sent.get() != null) return false;
      sent.set(call.getArgument(1));
      return true;
    });
    var service = new SmsOtpService(sender, mock(SmsOtpRateLimiter.class));
    var request = new MockHttpServletRequest();
    String destination = "/reservations/new?draft=testing";

    service.issue(request, null, "0901234567", destination);
    assertThrows(SmsOtpService.OtpException.class, () -> service.issue(request, null, "0901234567", null));
    assertEquals(destination, service.verify(request, null, sent.get()).returnTo());
  }

  @Test
  void aSuccessfulResendWithoutAQueryKeepsTheBookingDestination() {
    var sender = mock(EsmsOtpSender.class);
    when(sender.live()).thenReturn(true);
    var sent = new AtomicReference<String>();
    when(sender.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
      sent.set(call.getArgument(1));
      return true;
    });
    var service = new SmsOtpService(sender, mock(SmsOtpRateLimiter.class));
    var request = new MockHttpServletRequest();
    String destination = "/reservations/new?draft=testing";

    service.issue(request, null, "0901234567", destination);
    service.issue(request, null, "0901234567", null);
    assertEquals(destination, service.verify(request, null, sent.get()).returnTo());
  }
}
