package t3l.restaurant.t3l_restaurant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoTimeoutException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;
import t3l.restaurant.t3l_restaurant.repository.RestaurantTableRepository;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;
import t3l.restaurant.t3l_restaurant.service.ReservationSchedule;
import t3l.restaurant.t3l_restaurant.service.ReservationService;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:chatWeb;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.sql.init.mode=never", "app.chat.api-key=", "app.chat.vector-uri=", "spring.mail.username=",
    "app.jwt.secret=Y3VzdG9tZXItYWNjb3VudHMtdGVzdC1rZXktdGhpcy1pcy1mb3ItdGVzdHMtb25seQ=="
})
@AutoConfigureMockMvc
class ChatWebIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ReservationSchedule schedule;
  @Autowired ReservationService service;
  @Autowired ReservationRepository reservations;
  @Autowired RestaurantTableRepository tables;
  @Autowired UserAccountRepository accounts;
  @MockBean ChatStore store;

  @BeforeEach
  void history() {
    var turns = new ConcurrentHashMap<String, List<ChatStore.Turn>>();
    when(store.allow(anyString(), any(), anyInt())).thenReturn(true);
    when(store.history(anyString())).thenAnswer(call -> turns.getOrDefault(call.getArgument(0), List.of()));
    doAnswer(call -> {
      turns.compute(call.getArgument(0), (id, previous) -> {
        var next = new java.util.ArrayList<>(previous == null ? List.<ChatStore.Turn>of() : previous);
        next.add(call.getArgument(1));
        return next;
      });
      return null;
    }).when(store).append(anyString(), any());
    doAnswer(call -> turns.remove(call.getArgument(0))).when(store).clear(anyString());
  }

  @Test
  void protectsChatWithCsrfRolesIsolatedHistoryAndRateLimits() throws Exception {
    var guest = new MockHttpSession();
    mvc.perform(get("/chat/session").with(user("staff").roles("STAFF"))).andExpect(status().isForbidden());
    mvc.perform(get("/chat/session").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
    mvc.perform(post("/chat/messages").session(guest).contentType(MediaType.APPLICATION_JSON)
        .content("{\"text\":\"Chào\"}")).andExpect(status().isForbidden());
    mvc.perform(post("/chat/messages").session(guest).with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"text\":\"" + "a".repeat(801) + "\"}")).andExpect(status().isBadRequest());
    for (String invalid : List.of("{\"text\":123}", "{\"text\":null}", "{\"text\":[]}", "{}",
        "{\"text\":\"\\u0000\"}", "{\"text\":\"\\u00a0\"}", "{")) {
      mvc.perform(post("/chat/messages").session(guest).with(csrf()).contentType(MediaType.APPLICATION_JSON)
          .content(invalid)).andExpect(status().isBadRequest());
    }
    verify(store, never()).append(anyString(), any());
    mvc.perform(post("/chat/messages").session(guest).with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"text\":\"Chào\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.text").value(containsString("T3L")));
    mvc.perform(get("/chat/session").session(guest)).andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", containsString("no-store"))).andExpect(jsonPath("$.history[0].user").value("Chào"));
    mvc.perform(get("/chat/session")).andExpect(status().isOk()).andExpect(jsonPath("$.history").isEmpty());
    var buyer = customer();
    mvc.perform(get("/chat/session").session(guest).with(user(buyer.getUsername()).roles("BUYER")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.history").isEmpty());
    mvc.perform(delete("/chat/session").session(guest)).andExpect(status().isForbidden());
    mvc.perform(delete("/chat/session").session(guest).with(csrf())).andExpect(status().isOk());
    when(store.allow(anyString(), any(), anyInt())).thenReturn(false);
    mvc.perform(post("/chat/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"text\":\"Chào\"}")).andExpect(status().isTooManyRequests());
  }

  @Test
  void reportsMongoFailureAndRecovers() throws Exception {
    when(store.history(anyString())).thenThrow(new MongoTimeoutException("private database detail"));
    var guest = new MockHttpSession();
    mvc.perform(get("/chat/session").session(guest)).andExpect(status().isOk())
        .andExpect(jsonPath("$.conversationAvailable").value(false)).andExpect(jsonPath("$.history").isEmpty());
    mvc.perform(post("/chat/messages").session(guest).with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"text\":\"Chào\"}")).andExpect(status().isServiceUnavailable())
        .andExpect(content().string(not(containsString("private database detail"))));
    verify(store, never()).append(anyString(), any());
    doReturn(List.of()).when(store).history(anyString());
    mvc.perform(get("/chat/session").session(guest)).andExpect(status().isOk())
        .andExpect(jsonPath("$.conversationAvailable").value(true));
  }

  @Test
  void previewsAvailabilityAndPrefillsExistingFormWithoutCreatingReservation() throws Exception {
    var date = schedule.today().plusDays(10);
    var slot = schedule.getOpeningTime();
    var table = new RestaurantTable();
    table.configure("CHAT-QA", "Phòng chính", "Chuẩn", 20, true);
    tables.save(table);
    var guest = new MockHttpSession();
    long count = reservations.count();
    mvc.perform(post("/chat/booking-draft").session(guest).with(csrf())).andExpect(status().isConflict());
    var preview = mvc.perform(post("/chat/messages").session(guest).with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(new ChatController.Message("Đặt bàn " + date + " " + slot + " cho 16 khách"))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.draft").value(true)).andReturn();
    var oldToken = json.readTree(preview.getResponse().getContentAsString()).get("draftToken").asText();
    mvc.perform(post("/chat/messages").session(guest).with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(new ChatController.Message("17 khách"))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.draft").value(true));
    var snapshot = mvc.perform(get("/chat/session").session(guest)).andExpect(status().isOk())
        .andExpect(jsonPath("$.draftAvailable").value(true)).andReturn();
    var token = json.readTree(snapshot.getResponse().getContentAsString()).get("draftToken").asText();
    assertThat(token).isNotEqualTo(oldToken);
    mvc.perform(post("/chat/booking-draft").session(guest).with(csrf()).header("X-Chat-Draft", oldToken))
        .andExpect(status().isConflict());
    mvc.perform(post("/chat/booking-draft").with(csrf()).header("X-Chat-Draft", token)).andExpect(status().isConflict());
    var draft = mvc.perform(post("/chat/booking-draft").session(guest).with(csrf()).header("X-Chat-Draft", token))
        .andExpect(status().isOk()).andReturn();
    mvc.perform(post("/chat/booking-draft").session(guest).with(csrf()).header("X-Chat-Draft", token))
        .andExpect(status().isConflict());
    var url = json.readTree(draft.getResponse().getContentAsString()).get("url").asText();
    mvc.perform(get(url).session(guest)).andExpect(status().isOk())
        .andExpect(model().attributeExists("form"))
        .andExpect(result -> {
          var form = (ReservationForm) result.getModelAndView().getModel().get("form");
          assertThat(form.getDate()).isEqualTo(date);
          assertThat(form.getTime()).isEqualTo(slot);
          assertThat(form.getGuests()).isEqualTo(17);
          assertThat(form.isConsentAccepted()).isFalse();
        });
    assertThat(reservations.count()).isEqualTo(count);
  }

  @Test
  void searchesOwnedBookingsOnlyAndReportsOccupiedTable() throws Exception {
    var owner = customer();
    var other = customer();
    var table = new RestaurantTable();
    table.configure("CHAT-OCCUPIED", "Phòng riêng", "Chuẩn", 22, true);
    tables.save(table);
    var form = new ReservationForm();
    form.setDate(schedule.today().plusDays(12));
    form.setTime(schedule.getOpeningTime());
    form.setGuests(19);
    form.setGuestName("Bảo mật");
    form.setGuestPhone("0901111111");
    form.setConsentAccepted(true);
    var booking = service.create(form, owner);
    String lookup = json.writeValueAsString(new ChatController.Message("Tra cứu " + booking.getCode()));
    mvc.perform(post("/chat/messages").with(user(owner.getUsername()).roles("BUYER")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(lookup)).andExpect(status().isOk())
        .andExpect(jsonPath("$.text").value(containsString(booking.getCode())));
    mvc.perform(post("/chat/messages").with(user(other.getUsername()).roles("BUYER")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(lookup)).andExpect(status().isOk())
        .andExpect(jsonPath("$.text").value(not(containsString(booking.getCode()))));
    var ownerSession = new MockHttpSession();
    mvc.perform(post("/chat/messages").session(ownerSession).with(user(owner.getUsername()).roles("BUYER")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Xem lịch đã đặt\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.text").value(containsString(booking.getCode())));
    mvc.perform(get("/chat/session").session(ownerSession).with(user(owner.getUsername()).roles("BUYER")))
        .andExpect(status().isOk()).andExpect(content().string(not(containsString(booking.getCode()))));

    // Both server queries use the same capacity and overlap rules.
    for (var candidate : tables.findByActiveTrueOrderByCodeAsc()) {
      if (!candidate.getId().equals(booking.getTable().getId())) {
        candidate.setActive(false);
        tables.save(candidate);
      }
    }
    var availability = new ChatController.Message("Kiểm tra bàn trống " + form.getDate() + " " + form.getTime() + " cho 19 khách");
    mvc.perform(post("/chat/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(availability))).andExpect(status().isOk()).andExpect(jsonPath("$.draft").value(false))
        .andExpect(jsonPath("$.text").value(containsString("không còn bàn phù hợp")));
  }

  private UserAccount customer() {
    var id = UUID.randomUUID().toString();
    return accounts.save(UserAccount.customer("Bảo mật", id + "@example.test", "0901111111", id, "encoded"));
  }
}
