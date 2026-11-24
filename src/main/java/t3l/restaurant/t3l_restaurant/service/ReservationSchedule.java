package t3l.restaurant.t3l_restaurant.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.entity.RestaurantSettings;
import t3l.restaurant.t3l_restaurant.repository.RestaurantSettingsRepository;

@Component
public class ReservationSchedule {
  private final LocalTime openingTime;
  private final LocalTime closingTime;
  private final int durationMinutes;
  private final int slotMinutes;
  private final Clock clock;
  private RestaurantSettingsRepository settings;

  @Autowired
  public ReservationSchedule(
      @Value("${app.reservations.opening-time:10:00}") String opening,
      @Value("${app.reservations.closing-time:22:00}") String closing,
      @Value("${app.reservations.duration-minutes:120}") int duration,
      @Value("${app.reservations.slot-minutes:30}") int slot,
      @Value("${app.reservations.time-zone:Asia/Ho_Chi_Minh}") String zone, RestaurantSettingsRepository settings) {
    this(LocalTime.parse(opening), LocalTime.parse(closing), duration, slot,
        Clock.system(ZoneId.of(zone)));
    this.settings = settings;
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

  public Clock getClock() { return clock; }

  public record Rules(LocalTime opening, LocalTime closing, int duration, int slot,
                      int changeNotice, int checkIn, int noShow) {
    LocalTime lastArrival() { return closing.minusMinutes(duration); }
    public List<LocalTime> slots() {
      long count = ChronoUnit.MINUTES.between(opening, lastArrival()) / slot + 1;
      return java.util.stream.LongStream.range(0, count).mapToObj(index -> opening.plusMinutes(index * slot)).toList();
    }
  }

  public Rules rules() {
    var current = settings == null ? null : settings.findById(RestaurantSettings.PROFILE_ID).orElse(null);
    if (current == null) return new Rules(openingTime, closingTime, durationMinutes, slotMinutes, 90, 30, 15);
    return new Rules(current.getOpeningTime(), current.getClosingTime(), current.getDurationMinutes(),
        current.getSlotMinutes(), current.getChangeNoticeMinutes(), current.getCheckInMinutes(), current.getNoShowMinutes());
  }

  public LocalTime getOpeningTime() { return rules().opening(); }
  public LocalTime getClosingTime() { return rules().closing(); }
  public int getDurationMinutes() { return rules().duration(); }
  public int getSlotMinutes() { return rules().slot(); }
  public int getChangeNoticeMinutes() { return rules().changeNotice(); }
  public int getCheckInMinutes() { return rules().checkIn(); }
  public int getNoShowMinutes() { return rules().noShow(); }

  public LocalTime getLastArrival() {
    return rules().lastArrival();
  }

  public List<LocalTime> getTimeSlots() {
    return rules().slots();
  }

  public List<LocalTime> futureSlots(LocalDate date, Rules rules) {
    var now = LocalDateTime.now(clock);
    return rules.slots().stream().filter(slot -> date.atTime(slot).isAfter(now)).toList();
  }

  public LocalDateTime validateAndGetEnd(ReservationForm form) {
    if (form.getDate() == null || form.getDate().isBefore(today())) {
      throw new BookingException("date", "Vui lòng chọn ngày từ hôm nay trở đi.");
    }
    var current = rules();
    if (form.getTime() == null || !current.slots().contains(form.getTime())) {
      throw new BookingException("time", "Vui lòng chọn giờ đến từ " + current.opening()
          + " đến " + current.lastArrival() + ", cách nhau " + current.slot() + " phút.");
    }
    LocalDateTime start = LocalDateTime.of(form.getDate(), form.getTime());
    if (!start.isAfter(LocalDateTime.now(clock))) {
      throw new BookingException("time", "Giờ đến đã qua. Vui lòng chọn khung giờ tiếp theo.");
    }
    return start.plusMinutes(current.duration());
  }
}
