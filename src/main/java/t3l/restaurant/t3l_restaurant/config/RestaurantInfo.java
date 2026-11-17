package t3l.restaurant.t3l_restaurant.config;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import t3l.restaurant.t3l_restaurant.service.ReservationSchedule;

@Component("restaurantInfo")
@Getter
public class RestaurantInfo {
  private final String address;
  private final String phone;
  private final String email;
  private final ReservationSchedule schedule;

  public RestaurantInfo(@Value("${app.restaurant.address}") String address,
      @Value("${app.restaurant.phone:}") String phone,
      @Value("${app.restaurant.email:}") String email, ReservationSchedule schedule) {
    this.address = address.trim();
    this.phone = phone.trim();
    this.email = email.trim();
    this.schedule = schedule;
  }

  public String getPhoneHref() { return "tel:" + phone.replaceAll("[^+0-9]", ""); }
  public String getEmailHref() { return "mailto:" + email; }
  public String getHours() { return schedule.getOpeningTime() + " – " + schedule.getClosingTime(); }
  public String getMapsUrl() { return "https://www.google.com/maps/search/?api=1&query=" + mapQuery(); }
  public String getMapsEmbedUrl() { return "https://www.google.com/maps?q=" + mapQuery() + "&output=embed"; }
  private String mapQuery() { return URLEncoder.encode(address, StandardCharsets.UTF_8); }
}
