package t3l.restaurant.t3l_restaurant.config;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import t3l.restaurant.t3l_restaurant.service.ReservationSchedule;
import t3l.restaurant.t3l_restaurant.entity.RestaurantSettings;
import t3l.restaurant.t3l_restaurant.repository.RestaurantSettingsRepository;

@Component("restaurantInfo")
public class RestaurantInfo {
  private final String address;
  private final String phone;
  private final String email;
  private final ReservationSchedule schedule;
  private final RestaurantSettingsRepository settings;

  public RestaurantInfo(@Value("${app.restaurant.address}") String address,
      @Value("${app.restaurant.phone:}") String phone,
      @Value("${app.restaurant.email:}") String email, ReservationSchedule schedule, RestaurantSettingsRepository settings) {
    this.address = address.trim();
    this.phone = phone.trim();
    this.email = email.trim();
    this.schedule = schedule;
    this.settings = settings;
  }

  private RestaurantSettings profile() { return settings.findById(RestaurantSettings.PROFILE_ID).orElse(null); }
  public String getAddress() { var p = profile(); return p == null ? address : p.getAddress(); }
  public String getPhone() { var p = profile(); return p == null ? phone : p.getPhone(); }
  public String getEmail() { var p = profile(); return p == null ? email : p.getEmail(); }
  public String getAboutText() { var p = profile(); return p == null ? "" : p.getAboutText(); }
  public String getAboutImageUrl() { var p = profile(); return p == null ? "" : p.getAboutImageUrl(); }
  public String getPhoneHref() { return "tel:" + getPhone().replaceAll("[^+0-9]", ""); }
  public String getEmailHref() { return "mailto:" + getEmail(); }
  public String getHours() { var rules = schedule.rules(); return rules.opening() + " – " + rules.closing(); }
  public LocalTime getLastArrival() { return schedule.getLastArrival(); }
  public String getMapsUrl() { return "https://www.google.com/maps/search/?api=1&query=" + mapQuery(); }
  public String getMapsEmbedUrl() { return "https://www.google.com/maps?q=" + mapQuery() + "&output=embed"; }
  private String mapQuery() { return URLEncoder.encode(getAddress(), StandardCharsets.UTF_8); }
}
