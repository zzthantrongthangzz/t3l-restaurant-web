package t3l.restaurant.t3l_restaurant.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;

class ReservationScheduleTest {
  private final ReservationSchedule schedule = new ReservationSchedule(
      LocalTime.of(10, 0), LocalTime.of(22, 0), 120, 30,
      Clock.fixed(Instant.parse("2030-01-01T10:15:00Z"), ZoneId.of("Asia/Ho_Chi_Minh")));

  private ReservationForm form(String date, String time) {
    ReservationForm form = new ReservationForm();
    form.setDate(LocalDate.parse(date));
    form.setTime(LocalTime.parse(time));
    return form;
  }

  @Test
  void permitsLastArrivalAndEndsAtClosing() {
    var end = schedule.validateAndGetEnd(form("2030-01-01", "20:00"));
    assertEquals(LocalTime.of(22, 0), end.toLocalTime());
    assertEquals(21, schedule.getTimeSlots().size());
  }

  @Test
  void rejectsPastTimeUsingRestaurantTimeZone() {
    assertThrows(BookingException.class, () -> schedule.validateAndGetEnd(form("2030-01-01", "17:00")));
    assertThrows(BookingException.class, () -> schedule.validateAndGetEnd(form("2029-12-31", "18:00")));
    assertDoesNotThrow(() -> schedule.validateAndGetEnd(form("2030-01-02", "10:00")));
  }

  @Test
  void rejectsOffGridTimesAndArrivalsThatExceedClosing() {
    for (String time : new String[] {"09:30", "18:15", "18:00:01", "20:30", "23:00"}) {
      assertThrows(BookingException.class,
          () -> schedule.validateAndGetEnd(form("2030-01-02", time)), time);
    }
  }
}
