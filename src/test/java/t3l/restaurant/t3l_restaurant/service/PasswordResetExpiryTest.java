package t3l.restaurant.t3l_restaurant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import t3l.restaurant.t3l_restaurant.entity.CustomerProfile;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.CustomerProfileRepository;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;

class PasswordResetExpiryTest {
  @Test
  void codesAndPasswordGrantsExpireAfterFiveMinutes() {
    String phone = "0930000035";
    var account = mock(UserAccount.class);
    when(account.isEnabled()).thenReturn(true);
    when(account.getId()).thenReturn(10L);
    when(account.getUsername()).thenReturn("phone:" + phone);
    when(account.getRole()).thenReturn("BUYER");
    var profile = new CustomerProfile(account);
    profile.updateGoogle("verified-subject", "Reset QA", "expiry@example.test", null);
    var phones = mock(PhoneCustomerService.class);
    when(phones.findPasswordAccount(phone)).thenReturn(Optional.of(account));
    var profiles = mock(CustomerProfileRepository.class);
    when(profiles.findById(10L)).thenReturn(Optional.of(profile));
    var mail = mock(JavaMailSender.class);
    var clock = mock(Clock.class);
    var transactions = mock(TransactionTemplate.class);
    var service = new PasswordResetService(phones, mock(UserAccountRepository.class), profiles,
        mock(SmsOtpRateLimiter.class), mock(PasswordEncoder.class), transactions,
        new EmailOtpService(new SyncTaskExecutor(), mail, "test@example.test", "test-only"), clock);
    var request = new MockHttpServletRequest();
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    when(clock.instant()).thenReturn(start);
    service.start(request, phone, null);

    var messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail).send(messages.capture());
    when(clock.instant()).thenReturn(start.plusSeconds(300));
    assertThrows(PasswordResetService.ResetException.class, () -> service.verify(request, code(messages.getValue())));

    service.resend(request);
    verify(mail, times(2)).send(messages.capture());
    when(clock.instant()).thenReturn(start.plusSeconds(599));
    service.verify(request, code(messages.getValue()));
    assertEquals(PasswordResetService.Step.PASSWORD, service.view(request.getSession()).step());
    when(clock.instant()).thenReturn(start.plusSeconds(899));
    assertThrows(PasswordResetService.ResetException.class,
        () -> service.reset(request, "Replacement password 123!", "Replacement password 123!"));
    verifyNoInteractions(transactions);
    assertEquals(PasswordResetService.Step.PHONE, service.view(request.getSession()).step());
  }

  private static String code(SimpleMailMessage message) {
    return message.getText().lines().findFirst().orElseThrow().split(": ")[1];
  }
}
