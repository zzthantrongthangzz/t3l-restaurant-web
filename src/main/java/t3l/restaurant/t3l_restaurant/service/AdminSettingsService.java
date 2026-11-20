package t3l.restaurant.t3l_restaurant.service;

import jakarta.validation.Validator;
import java.time.temporal.ChronoUnit;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import t3l.restaurant.t3l_restaurant.config.RestaurantInfo;
import t3l.restaurant.t3l_restaurant.dto.RestaurantSettingsForm;
import t3l.restaurant.t3l_restaurant.entity.RestaurantSettings;
import t3l.restaurant.t3l_restaurant.repository.RestaurantSettingsRepository;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;
import t3l.restaurant.t3l_restaurant.repository.RestaurantTableRepository;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminSettingsService {
  private final RestaurantSettingsRepository settings;
  private final RestaurantInfo restaurant;
  private final ReservationSchedule schedule;
  private final Validator validator;
  private final AdminAudit audit;
  private final ReservationRepository reservations;
  private final RestaurantTableRepository tables;

  @Transactional(readOnly = true)
  public RestaurantSettingsForm form() {
    var form = new RestaurantSettingsForm();
    var rules = schedule.rules();
    form.setAddress(restaurant.getAddress());
    form.setPhone(restaurant.getPhone());
    form.setEmail(restaurant.getEmail());
    form.setAboutText(restaurant.getAboutText());
    form.setAboutImageUrl(restaurant.getAboutImageUrl());
    form.setOpeningTime(rules.opening());
    form.setClosingTime(rules.closing());
    form.setDurationMinutes(rules.duration());
    form.setSlotMinutes(rules.slot());
    form.setChangeNoticeMinutes(rules.changeNotice());
    form.setCheckInMinutes(rules.checkIn());
    form.setNoShowMinutes(rules.noShow());
    return form;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void save(RestaurantSettingsForm form) {
    validator.validate(form).stream().findFirst().ifPresent(error -> { throw new IllegalArgumentException(error.getMessage()); });
    long serviceMinutes = ChronoUnit.MINUTES.between(form.getOpeningTime(), form.getClosingTime());
    if (serviceMinutes < form.getDurationMinutes() || form.getOpeningTime().getSecond() != 0
        || form.getClosingTime().getSecond() != 0 || form.getOpeningTime().getNano() != 0
        || form.getClosingTime().getNano() != 0) {
      throw new IllegalArgumentException("Giờ đóng cửa phải sau giờ mở cửa trong cùng ngày và đủ thời gian cho một lượt phục vụ.");
    }
    AdminInput.validateImage(form.getAboutImageUrl());
    tables.lockInventory();
    checkExistingBookings(form);
    var profile = settings.findById(RestaurantSettings.PROFILE_ID).orElseGet(RestaurantSettings::new);
    profile.update(form);
    settings.save(profile);
    audit.record("Cập nhật cấu hình", "Thông tin nhà hàng và quy định đặt bàn");
  }

  private void checkExistingBookings(RestaurantSettingsForm form) {
    var previous = schedule.rules();
    if (previous.opening().equals(form.getOpeningTime()) && previous.closing().equals(form.getClosingTime())) return;
    var now = LocalDateTime.now(schedule.getClock());
    var affected = reservations.findByStatusInAndReservationDateGreaterThanEqual(
        ReservationService.BLOCKING_STATUSES, now.toLocalDate()).stream()
        .filter(booking -> booking.effectiveEndsAt(previous.duration()).isAfter(now))
        .filter(booking -> booking.getReservationTime().isBefore(form.getOpeningTime())
            || booking.effectiveEndsAt(previous.duration()).isAfter(booking.getReservationDate().atTime(form.getClosingTime())))
        .findFirst();
    if (affected.isPresent()) {
      throw new IllegalArgumentException("Giờ mới nằm ngoài lịch của " + affected.get().getCode() + ". Hãy xử lý đặt bàn này trước.");
    }
  }
}
