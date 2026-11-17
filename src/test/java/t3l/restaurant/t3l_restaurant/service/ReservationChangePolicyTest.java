package t3l.restaurant.t3l_restaurant.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import t3l.restaurant.t3l_restaurant.entity.Reservation;

class ReservationChangePolicyTest {
  private ReservationSchedule schedule(Instant now) {
    return new ReservationSchedule(LocalTime.of(10, 0), LocalTime.of(22, 0), 120, 30,
        Clock.fixed(now, ZoneId.of("Asia/Ho_Chi_Minh")));
  }
  private final ReservationSchedule schedule = schedule(Instant.parse("2030-01-01T03:00:00Z"));
  private final ReservationChangePolicy policy = new ReservationChangePolicy(schedule);
  private Reservation booking(LocalTime time) {
    return new Reservation("T3L-0000000000000001", "Test", "0901234567", null, null,
        LocalDate.of(2030, 1, 1), time, 2, null, true);
  }
  @Test void acceptsExactlyNinetyMinutesButBlocksOneMinuteLess() {
    assertNull(policy.blockedReason(booking(LocalTime.of(11, 30))));
    assertNotNull(policy.blockedReason(booking(LocalTime.of(11, 29))));
    assertDoesNotThrow(() -> policy.requireProposedNotice(LocalDateTime.of(2030, 1, 1, 11, 30)));
    assertThrows(BookingException.class, () -> policy.requireProposedNotice(LocalDateTime.of(2030, 1, 1, 11, 29)));
  }
  @Test void blocksExpiredCancelledSeatedAndCompletedBookings() {
    assertNotNull(policy.blockedReason(booking(LocalTime.of(9, 30))));
    for (String state : new String[] {"CANCELLED", "COMPLETED", "SEATED", "CHECKED_IN"}) {
      var booking = booking(LocalTime.of(18, 0));
      booking.updateStatus(state);
      assertNotNull(policy.blockedReason(booking));
    }
  }
  @Test void lookupGrantExpiresAndConfirmationPermissionDoesNotAuthorizeChanges() {
    var session = new MockHttpSession();
    String code = "T3L-0000000000000001";
    new ReservationConfirmationAccess().grant(session, code);
    var access = new ReservationLookupAccess(schedule);
    assertFalse(access.canView(session, code));
    access.grant(session, code);
    assertTrue(access.canView(session, code));
    assertFalse(new ReservationLookupAccess(schedule(Instant.parse("2030-01-01T03:31:00Z"))).canView(session, code));
  }
}
