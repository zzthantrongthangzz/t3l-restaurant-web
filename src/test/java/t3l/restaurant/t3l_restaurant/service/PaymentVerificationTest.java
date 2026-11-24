package t3l.restaurant.t3l_restaurant.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import t3l.restaurant.t3l_restaurant.entity.PaymentIntent;
import t3l.restaurant.t3l_restaurant.repository.DiningOrderRepository;
import t3l.restaurant.t3l_restaurant.repository.PaymentIntentRepository;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class PaymentVerificationTest {
  private static final LocalDateTime NOW = LocalDateTime.of(2032, 2, 13, 11, 0);
  private static final BigDecimal AMOUNT = BigDecimal.valueOf(500_000);
  private final PaymentIntentRepository intents = mock(PaymentIntentRepository.class);
  private final ReservationRepository reservations = mock(ReservationRepository.class);
  private final ReservationService bookings = mock(ReservationService.class);
  private final SepayProperties properties = mock(SepayProperties.class);
  private final ReservationSchedule schedule = mock(ReservationSchedule.class);
  private final PaymentIntent intent = new PaymentIntent("T3LQA001", PaymentIntent.DEPOSIT, 9L, null,
      AMOUNT, NOW.minusMinutes(1), NOW.plusMinutes(5));
  private final PaymentVerificationService verification = new PaymentVerificationService(
      intents, reservations, mock(DiningOrderRepository.class), bookings, properties, schedule);

  @BeforeEach
  void prepare() {
    var zone = ZoneId.of("Asia/Ho_Chi_Minh");
    when(schedule.getClock()).thenReturn(Clock.fixed(NOW.atZone(zone).toInstant(), zone));
    when(properties.bankAccount()).thenReturn("012345");
    when(intents.findReservationId(7L)).thenReturn(Optional.of(9L));
    when(intents.lockById(7L)).thenReturn(Optional.of(intent));
  }

  @Test
  void creditsAnExactIncomingDepositOnlyOnce() {
    when(bookings.receiveVerifiedDeposit(9L, AMOUNT, "bank-ref", NOW, false)).thenReturn(true);
    verification.process(7L, transfer(AMOUNT), false);

    assertEquals(PaymentIntent.RECEIVED, intent.getStatus());
    var locks = inOrder(reservations, intents, bookings);
    locks.verify(reservations).lockById(9L);
    locks.verify(intents).lockById(7L);
    locks.verify(bookings).receiveVerifiedDeposit(9L, AMOUNT, "bank-ref", NOW, false);
    verification.process(7L, transfer(AMOUNT), false);
    verify(bookings, times(1)).receiveVerifiedDeposit(9L, AMOUNT, "bank-ref", NOW, false);
  }

  @Test
  void sendsAnUnderpaymentToReviewWithoutCreditingIt() {
    verification.process(7L, transfer(AMOUNT.subtract(BigDecimal.ONE)), false);

    assertEquals(PaymentIntent.REVIEW_REQUIRED, intent.getStatus());
    verifyNoInteractions(bookings);
  }

  private PaymentVerificationService.Incoming transfer(BigDecimal amount) {
    return new PaymentVerificationService.Incoming("transaction-1", "bank-ref", "012345", "in",
        amount, BigDecimal.ZERO, "Thanh toan T3LQA001", NOW);
  }
}
