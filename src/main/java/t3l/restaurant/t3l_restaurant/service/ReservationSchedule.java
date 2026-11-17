package t3l.restaurant.t3l_restaurant.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;

@Component
@Getter
public class ReservationSchedule {
  private final LocalTime openingTime;
  private final LocalTime closingTime;
  private final int durationMinutes;
  private final int slotMinutes;
  private final Clock clock;

  @Autowired
  public ReservationSchedule(
      @Value("${app.reservations.opening-time:10:00}") String opening,
      @Value("${app.reservations.closing-time:22:00}") String closing,
      @Value("${app.reservations.duration-minutes:120}") int duration,
      @Value("${app.reservations.slot-minutes:30}") int slot,
      @Value("${app.reservations.time-zone:Asia/Ho_Chi_Minh}") String zone) {
    this(LocalTime.parse(opening), LocalTime.parse(closing), duration, slot,
        Clock.system(ZoneId.of(zone)));
  }

  ReservationSchedule(LocalTime opening, LocalTime closing, int duration, int slot, Clock clock) {
    if (!opening.isBefore(closing) || duration <= 0 || slot <= 0
        || duration > ChronoUnit.MINUTES.between(opening, closing)) {
      throw new IllegalArgumentException("Cấu hình giờ đặt bàn không hợp lệ.");
    }
    this.openingTime = opening;
    this.closingTime = closing;
    this.durationMinutes = duration;
    this.slotMinutes = slot;
    this.clock = clock;
  }

  public LocalDate today() {
    return LocalDate.now(clock);
  }

  public String getTimeZone() {
    return clock.getZone().getId();
  }

  public LocalTime getLastArrival() {
    return closingTime.minusMinutes(durationMinutes);
  }

  public List<LocalTime> getTimeSlots() {
    long count = ChronoUnit.MINUTES.between(openingTime, getLastArrival()) / slotMinutes + 1;
    return java.util.stream.LongStream.range(0, count)
        .mapToObj(index -> openingTime.plusMinutes(index * slotMinutes)).toList();
  }

  public LocalDateTime validateAndGetEnd(ReservationForm form) {
    if (form.getDate() == null || form.getDate().isBefore(today())) {
      throw new BookingException("date", "Vui lòng chọn ngày từ hôm nay trở đi.");
    }
    if (form.getTime() == null || !getTimeSlots().contains(form.getTime())) {
      throw new BookingException("time", "Vui lòng chọn giờ đến từ " + openingTime
          + " đến " + getLastArrival() + ", cách nhau " + slotMinutes + " phút.");
    }
    LocalDateTime start = LocalDateTime.of(form.getDate(), form.getTime());
    if (!start.isAfter(LocalDateTime.now(clock))) {
      throw new BookingException("time", "Giờ đến đã qua. Vui lòng chọn khung giờ tiếp theo.");
    }
    return start.plusMinutes(durationMinutes);
  }
}
