package t3l.restaurant.t3l_restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import t3l.restaurant.t3l_restaurant.dto.TrackingInput;
import t3l.restaurant.t3l_restaurant.entity.*;
import t3l.restaurant.t3l_restaurant.repository.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:tracking;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.data.mongodb.uri=mongodb://127.0.0.1:27099/tracking?serverSelectionTimeoutMS=200",
    "app.tracking.enabled=true", "app.chat.api-key=", "app.chat.vector-uri="
})
@AutoConfigureMockMvc
class TrackingIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserAccountRepository accounts;
  @Autowired DishRepository dishes;
  @Autowired ReservationRepository reservations;
  @Autowired TrackingService tracking;
  @Autowired TrackingReportService reports;

  @BeforeEach
  void clearAnalytics() {
    jdbc.update("DELETE FROM tracking_events");
    jdbc.update("DELETE FROM tracking_sessions");
  }

  @Test
  void collectsOnlyAfterConsentDeduplicatesAndLinksTheBuyer() throws Exception {
    String uniqueBuyer = "analytics-buyer-" + UUID.randomUUID();
    var buyer = accounts.save(UserAccount.customer("Analytics QA", null, null, uniqueBuyer, "unused"));
    var dish = new Dish();
    dish.update("Gà nướng", "Test menu", BigDecimal.valueOf(120000), null, null, null, "Món chính", true);
    dish = dishes.save(dish);

    var view = event("PAGE_VIEW", "menu", null, null, null);
    var search = event("SEARCH", "menu", null, "Gà", null);
    var privateSearch = event("SEARCH", "menu", null, "0901234567", null);
    var spoof = event("BOOKING_SUBMITTED", "booking", null, null, null);
    var click = event("DISH_CLICK", "menu", dish.getId(), null, null);
    var active = event("ACTIVE", "menu", null, null, 6);
    var payload = batch(view, search, privateSearch, spoof, click, active);

    mvc.perform(post("/tracking/events").with(csrf()).contentType("application/json").content(payload))
        .andExpect(status().isForbidden());
    mvc.perform(post("/tracking/preferences").contentType("application/json").content("{\"allowed\":true}"))
        .andExpect(status().isForbidden());
    Cookie consent = allow();
    for (int retry = 0; retry < 2; retry++) {
      mvc.perform(post("/tracking/events").cookie(consent).with(csrf()).contentType("application/json").content(payload))
          .andExpect(status().isNoContent());
    }
    assertThat(jdbc.queryForList("SELECT event_type FROM tracking_events", String.class))
        .containsExactlyInAnyOrder("PAGE_VIEW","SEARCH","DISH_CLICK","ACTIVE");
    assertThat(jdbc.queryForObject("SELECT term FROM tracking_events WHERE event_type='SEARCH'", String.class)).isEqualTo("ga");

    mvc.perform(post("/tracking/events").cookie(consent).with(csrf()).with(user(uniqueBuyer).roles("BUYER"))
        .contentType("application/json").content(batch(event("PAGE_VIEW","account",null,null,null))))
        .andExpect(status().isNoContent());
    assertThat(jdbc.queryForObject("SELECT user_id FROM tracking_sessions", Long.class)).isEqualTo(buyer.getId());
    var report = reports.report(reports.filter("today",null,null,"","day"));
    assertThat(report.summary().views()).isEqualTo(2);
    assertThat(report.summary().visitors()).isEqualTo(1);
    assertThat(report.summary().sessions()).isEqualTo(1);
    assertThat(report.summary().activeAverage()).isEqualTo(6);
    assertThat(report.dishes()).anyMatch(metric -> metric.secondary()==1);
    assertThat(reports.summary(reports.filter("today",null,null,"GUEST","day")).views()).isEqualTo(1);

    mvc.perform(get("/tracking/preferences").with(user("local-sms:0900000000").roles("BUYER")))
        .andExpect(jsonPath("$.choice").value("disabled"));
    mvc.perform(post("/tracking/events").cookie(consent).with(csrf()).with(user("employee").roles("STAFF"))
        .contentType("application/json").content(payload)).andExpect(status().isForbidden());
    String revoked = mvc.perform(post("/tracking/preferences").cookie(consent).with(csrf())
        .contentType("application/json").content("{\"allowed\":false}")).andExpect(status().isNoContent())
        .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
    assertThat(revoked).contains("t3l_analytics=off");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tracking_sessions WHERE ended_at IS NOT NULL", Long.class)).isEqualTo(1);
    mvc.perform(post("/tracking/events").cookie(new Cookie("t3l_analytics","off")).with(csrf())
        .contentType("application/json").content(payload)).andExpect(status().isForbidden());
  }

  @Test
  void reportsValidatedBookingOutcomesAndIgnoresPendingAndDemoForAbandonment() throws Exception {
    Cookie consent = allow();
    var request = new MockHttpServletRequest();
    request.setCookies(consent);
    request.setSession(new MockHttpSession());
    tracking.batch(request,null,new TrackingInput.Batch(List.of(event("PAGE_VIEW","booking",null,null,null))));
    tracking.booking(request,null,true,null);
    var valid = booking();
    tracking.booking(request,null,true,valid);
    tracking.depositOpened(request,null,valid.getId());

    tracking.booking(request,null,true,null);
    tracking.booking(request,null,true,booking());
    tracking.booking(request,null,true,null);
    var demo = booking();
    tracking.booking(request,null,true,demo);

    tracking.booking(request,null,false,null);
    jdbc.update("""
        UPDATE tracking_events SET occurred_at=?
        WHERE event_type='BOOKING_START' AND flow_key NOT IN
          (SELECT flow_key FROM tracking_events WHERE event_type='BOOKING_SUBMITTED')
        """, TrackingService.now().minusMinutes(40));
    jdbc.update("UPDATE reservations SET deposit_demo=TRUE,status='CONFIRMED',deposit_paid=100 WHERE id=?",demo.getId());

    var today = TrackingService.now().toLocalDate();
    var filter = reports.filter("custom",today.minusDays(1),today,"","day");
    assertThat(jdbc.queryForList("SELECT event_type FROM tracking_events", String.class))
        .contains("BOOKING_START", "BOOKING_SUBMITTED", "DEPOSIT_START");
    var initial = reports.report(filter);
    assertThat(initial.summary().abandoned()).isEqualTo(1);
    assertThat(initial.summary().abandonment()).isEqualTo(33.3);
    assertThat(initial.summary().conversion()).isZero();
    jdbc.update("UPDATE reservations SET status='CONFIRMED',deposit_paid=100 WHERE id=?",valid.getId());
    var confirmed = reports.report(filter);
    assertThat(confirmed.summary().conversion()).isEqualTo(100);
    assertThat(confirmed.branches().getFirst().steps()).extracting(TrackingReportService.Step::count)
        .containsExactly(2L,1L,1L,1L);

    mvc.perform(get("/admin/analytics")).andExpect(status().is3xxRedirection());
    mvc.perform(get("/admin/analytics.csv").with(user("employee").roles("STAFF"))).andExpect(status().isForbidden());
    mvc.perform(get("/admin/analytics").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk()).andExpect(content().string(containsString("Phân tích hành vi")));
    mvc.perform(get("/admin/analytics").header("X-T3L-Fragment","main").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk()).andExpect(header().string("X-T3L-Fragment","main"));
    mvc.perform(get("/admin/analytics.csv").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,containsString("t3l-analytics")));
  }

  @Test
  void rotatesIdleSessionsLimitsBatchesAndDropsExpiredData() throws Exception {
    Cookie consent = allow();
    for (int i = 0; i < 12; i++) {
      mvc.perform(post("/tracking/events").with(csrf()).cookie(consent).contentType("application/json")
          .content(batch(event("PAGE_VIEW","menu",null,null,null)))).andExpect(status().isNoContent());
    }
    mvc.perform(post("/tracking/events").with(csrf()).cookie(consent).contentType("application/json")
        .content(batch(event("PAGE_VIEW","menu",null,null,null)))).andExpect(status().isTooManyRequests());
    jdbc.update("UPDATE tracking_sessions SET last_activity_at=?", TrackingService.now().minusDays(91));
    mvc.perform(post("/tracking/events").with(csrf()).cookie(consent).contentType("application/json")
        .content(batch(event("PAGE_VIEW","menu",null,null,null)))).andExpect(status().isNoContent());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tracking_sessions",Long.class)).isEqualTo(2);
    tracking.clean();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tracking_sessions",Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tracking_events",Long.class)).isEqualTo(1);
  }

  private Reservation booking() {
    var booking = new Reservation("QA-" + UUID.randomUUID().toString().substring(0,12),"Analytics QA","0900000000",
        null,null,TrackingService.now().toLocalDate().plusDays(1),LocalTime.NOON,2,null,true);
    booking.priceDeposit(BigDecimal.valueOf(100), TrackingService.now().plusHours(1));
    return reservations.save(booking);
  }

  private Cookie allow() throws Exception {
    String header = mvc.perform(post("/tracking/preferences").with(csrf()).contentType("application/json")
        .content("{\"allowed\":true}")).andExpect(status().isNoContent())
        .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
    assertThat(header).contains("HttpOnly", "SameSite=Lax");
    return new Cookie("t3l_analytics",header.substring(header.indexOf('=')+1,header.indexOf(';')));
  }

  private String batch(TrackingInput.Event... events) throws Exception {
    return json.writeValueAsString(new TrackingInput.Batch(List.of(events)));
  }

  private TrackingInput.Event event(String type, String page, Long dish, String term, Integer seconds) {
    return new TrackingInput.Event(UUID.randomUUID(),type,page,dish,term,null,seconds);
  }
}
