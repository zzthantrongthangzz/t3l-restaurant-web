package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.time.LocalTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import t3l.restaurant.t3l_restaurant.dto.RestaurantSettingsForm;

@Entity
@Table(name = "restaurant_settings")
@Getter
@NoArgsConstructor
public class RestaurantSettings {
  public static final int PROFILE_ID = 1;
  @Id private Integer id = PROFILE_ID;
  @Column(nullable = false, length = 300) private String address;
  @Column(nullable = false, length = 30) private String phone;
  @Column(nullable = false, length = 254) private String email;
  @Column(name = "about_text", nullable = false, length = 2000) private String aboutText;
  @Column(name = "about_image_url", nullable = false, length = 500) private String aboutImageUrl;
  @Column(name = "opening_time", nullable = false) private LocalTime openingTime;
  @Column(name = "closing_time", nullable = false) private LocalTime closingTime;
  @Column(name = "duration_minutes", nullable = false) private int durationMinutes;
  @Column(name = "slot_minutes", nullable = false) private int slotMinutes;
  @Column(name = "change_notice_minutes", nullable = false) private int changeNoticeMinutes;
  @Column(name = "check_in_minutes", nullable = false) private int checkInMinutes;
  @Column(name = "no_show_minutes", nullable = false) private int noShowMinutes;
  @Version private Long version;

  public void update(RestaurantSettingsForm form) {
    address = form.getAddress().strip();
    phone = value(form.getPhone());
    email = value(form.getEmail());
    aboutText = value(form.getAboutText());
    aboutImageUrl = value(form.getAboutImageUrl());
    openingTime = form.getOpeningTime();
    closingTime = form.getClosingTime();
    durationMinutes = form.getDurationMinutes();
    slotMinutes = form.getSlotMinutes();
    changeNoticeMinutes = form.getChangeNoticeMinutes();
    checkInMinutes = form.getCheckInMinutes();
    noShowMinutes = form.getNoShowMinutes();
  }

  private String value(String value) { return value == null ? "" : value.strip(); }
}
