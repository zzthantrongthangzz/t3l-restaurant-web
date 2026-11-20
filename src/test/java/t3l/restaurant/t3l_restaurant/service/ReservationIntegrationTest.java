package t3l.restaurant.t3l_restaurant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;
import t3l.restaurant.t3l_restaurant.repository.RestaurantTableRepository;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;

@SpringBootTest
@AutoConfigureMockMvc
class ReservationIntegrationTest {
  @Autowired ReservationService service;
  @Autowired ReservationRepository reservations;
  @Autowired RestaurantTableRepository tables;
  @Autowired TransactionTemplate transaction;
  @Autowired MockMvc mvc;
  @Autowired ReservationConfirmationAccess confirmationAccess;
  @Autowired UserAccountRepository accounts;

  private final ConcurrentLinkedQueue<Long> createdIds = new ConcurrentLinkedQueue<>();
  private final List<Long> createdUsers = new java.util.ArrayList<>();
  private LocalDate date;

  @BeforeEach
  void chooseUnusedFutureDate() {
    date = LocalDate.of(2098, 1, 1);
    while (!reservations.findByReservationDateBetweenAndStatusNot(date, date, "CANCELLED").isEmpty()) {
      date = date.plusDays(1);
    }
  }

  @AfterEach
  void removeOnlyBookingsCreatedByThisTest() {
    transaction.executeWithoutResult(status -> reservations.deleteAllByIdInBatch(List.copyOf(createdIds)));
    createdIds.clear();
    accounts.deleteAllByIdInBatch(createdUsers);
    createdUsers.clear();
  }

  private ReservationForm form(LocalTime time, int guests, String area) {
    ReservationForm form = new ReservationForm();
    form.setDate(date);
    form.setTime(time);
    form.setGuests(guests);
    form.setPreferredArea(area);
    form.setGuestName("T3L integration test");
    form.setGuestPhone("0901234567");
    form.setGuestEmail("");
    form.setConsentAccepted(true);
    return form;
  }

  private Reservation book(LocalTime time, int guests, String area) {
    Reservation saved = service.create(form(time, guests, area));
    createdIds.add(saved.getId());
    return saved;
  }

  private void occupy(RestaurantTable table, LocalTime time) {
    transaction.executeWithoutResult(status -> {
      Reservation booking = new Reservation("QA-" + UUID.randomUUID().toString().substring(0, 16),
          "T3L integration fixture", "0901234567", null, table, date, time, 1, "Test fixture", true);
      booking.setBookingDetails(date.atTime(time).plusHours(2), null);
      createdIds.add(reservations.saveAndFlush(booking).getId());
    });
  }

  @Test
  void reusesTableAtBoundaryButNeverDuringOverlap() {
    Reservation first = book(LocalTime.of(18, 0), 2, "");
    Reservation overlapping = book(LocalTime.of(19, 30), 2, "");
    Reservation next = book(LocalTime.of(20, 0), 2, "");
    assertNotEquals(first.getTable().getId(), overlapping.getTable().getId());
    assertEquals(first.getTable().getId(), next.getTable().getId());
    assertEquals("PENDING_CONFIRMATION", first.getStatus());
    assertEquals(3, java.util.Set.of(first.getCode(), overlapping.getCode(), next.getCode()).size());
  }

  @Test
  void prefersRequestedAreaAndFallsBackWhenItIsFull() {
    var vipTables = tables.findByActiveTrueOrderByCodeAsc().stream()
        .filter(table -> "Phòng VIP".equals(table.getTableType())).toList();
    assertFalse(vipTables.isEmpty());
    Reservation preferred = book(LocalTime.of(12, 0), 2, "Phòng VIP");
    assertEquals("Phòng VIP", preferred.getTable().getTableType());
    vipTables.forEach(table -> occupy(table, LocalTime.of(18, 0)));
    Reservation fallback = book(LocalTime.of(18, 0), 2, "Phòng VIP");
    assertNotEquals("Phòng VIP", fallback.getTable().getTableType());
    assertEquals("Phòng VIP", fallback.getPreferredArea());
  }

  @Test
  void validatesCapacityContactAndUnknownArea() {
    assertThrows(BookingException.class, () -> service.create(form(LocalTime.of(18, 0), 20, "")));
    assertThrows(BookingException.class, () -> service.create(form(LocalTime.of(18, 0), 2, "Unknown area")));
    var invalid = form(LocalTime.of(18, 0), 2, "");
    invalid.setGuestPhone("----------");
    assertThrows(BookingException.class, () -> service.create(invalid));
  }

  @Test
  void concurrentRequestsCannotClaimTheLastTableTwice() throws Exception {
    var active = tables.findByActiveTrueOrderByCodeAsc();
    assertFalse(active.isEmpty());
    Long remainingId = active.stream().filter(table -> table.getCapacity() >= 2).findFirst().orElseThrow().getId();
    active.stream().filter(table -> !table.getId().equals(remainingId))
        .forEach(table -> occupy(table, LocalTime.of(18, 0)));
    var ready = new CountDownLatch(8);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(8)) {
      var futures = java.util.stream.IntStream.range(0, 8).mapToObj(index -> executor.submit(() -> {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test start timed out");
        try {
          return book(LocalTime.of(18, 0), 2, "");
        } catch (BookingException unavailable) {
          assertNull(unavailable.getField());
          return null;
        }
      })).toList();
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      int success = 0;
      for (var future : futures) {
        Reservation saved = future.get(20, TimeUnit.SECONDS);
        if (saved != null) {
          success++;
          assertEquals(remainingId, saved.getTable().getId());
        }
      }
      assertEquals(1, success);
    }
  }

  private String csrf(MockHttpSession session) throws Exception {
    String html = mvc.perform(get("/reservations/new").session(session))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    var match = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
    assertTrue(match.find());
    return match.group(1);
  }

  @Test
  void customerCanSubmitWithoutEmailAndStaffCanConfirmThenCancel() throws Exception {
    var guest = new MockHttpSession();
    var customer = accounts.save(UserAccount.customer("T3L MVC test", null, "0901234567", "qa:" + UUID.randomUUID(), "unused"));
    createdUsers.add(customer.getId());
    var customerContext = SecurityContextHolder.createEmptyContext();
    customerContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(customer.getUsername(), "",
        List.of(new SimpleGrantedAuthority("ROLE_BUYER"))));
    guest.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, customerContext);
    String draft = (String) mvc.perform(get("/reservations/new").session(guest)).andExpect(status().isOk())
        .andReturn().getModelAndView().getModel().get("bookingDraft");
    assertNotNull(draft);
    var result = mvc.perform(post("/reservations").session(guest).param("_csrf", csrf(guest))
            .param("draft", draft)
            .param("date", date.toString()).param("time", "18:00").param("guests", "2")
            .param("guestName", "T3L MVC test").param("guestPhone", "0901234567")
            .param("guestEmail", "").param("preferredArea", "Phòng VIP")
            .param("note", "Ghế trẻ em. Không dùng đậu phộng.")
            .param("status", "CONFIRMED").param("consentAccepted", "true"))
        .andExpect(status().is3xxRedirection()).andReturn();
    String redirect = result.getResponse().getRedirectedUrl();
    assertNotNull(redirect);
    String code = redirect.substring(redirect.indexOf("code=") + 5);
    Reservation saved = service.findByCode(code);
    assertNotNull(saved);
    createdIds.add(saved.getId());
    assertEquals("PENDING_CONFIRMATION", saved.getStatus());
    mvc.perform(get(redirect).session(guest)).andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(header().string("Referrer-Policy", "no-referrer"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Chờ xác nhận")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("T3L MVC test")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("0901234567")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Phòng VIP")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Ghế trẻ em. Không dùng đậu phộng.")));
    mvc.perform(get(redirect).session(new MockHttpSession())).andExpect(status().isNotFound())
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("T3L MVC test"))))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("0901234567"))))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(code))));
    mvc.perform(get(redirect).session(guest).param("status", "CANCELLED")).andExpect(status().isOk());
    assertEquals("PENDING_CONFIRMATION", service.findByCode(code).getStatus());
    mvc.perform(post("/staff/reservations/" + saved.getId() + "/status").session(guest)
            .param("_csrf", csrf(guest)).param("status", "CONFIRMED"))
        .andExpect(status().isForbidden());

    var staff = new MockHttpSession();
    String staffCsrf = csrf(staff);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("test-staff", "",
        List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
    staff.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    mvc.perform(get("/staff/reservations").session(staff)).andExpect(status().isOk());
    for (String state : List.of("CONFIRMED", "CANCELLED")) {
      mvc.perform(post("/staff/reservations/" + saved.getId() + "/status").session(staff)
              .param("_csrf", staffCsrf).param("status", state))
          .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/staff/reservations"));
      assertEquals(state, service.findByCode(code).getStatus());
      mvc.perform(get(redirect).session(guest)).andExpect(status().isOk())
          .andExpect(content().string(org.hamcrest.Matchers.containsString(
              "CONFIRMED".equals(state) ? "Đã xác nhận" : "Đã hủy")))
          .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
              "Yêu cầu đặt bàn đã được lưu thành công và đang chờ xác nhận."))));
    }
    assertThrows(IllegalArgumentException.class, () -> service.updateStatus(saved.getId(), "CONFIRMED"));
    Reservation replacement = book(LocalTime.of(18, 0), 2, "Phòng VIP");
    assertEquals(saved.getTable().getId(), replacement.getTable().getId());
  }

  @Test
  void invalidArrivalIsRedisplayedWithInputsPreserved() throws Exception {
    var session = new MockHttpSession();
    mvc.perform(post("/reservations").session(session).param("_csrf", csrf(session))
            .param("date", date.toString()).param("time", "20:30").param("guests", "2")
            .param("note", "Retained note")
            .param("consentAccepted", "true"))
        .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("form", "time"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Retained note")));
  }

  @Test
  void missingInvalidAndForeignCodesShareTheSamePrivateErrorPage() throws Exception {
    Reservation other = book(LocalTime.of(18, 0), 2, "");
    var stranger = new MockHttpSession();
    String unavailable = mvc.perform(get("/reservations/confirmation").session(stranger))
        .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
    for (String code : List.of("invalid", "T3L-0000000000000000", other.getCode(), "<script>alert(1)</script>")) {
      String html = mvc.perform(get("/reservations/confirmation").param("code", code).session(stranger))
          .andExpect(status().isNotFound())
          .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
          .andReturn().getResponse().getContentAsString();
      assertEquals(unavailable, html);
      assertFalse(html.contains(other.getGuestName()));
      assertFalse(html.contains(other.getGuestPhone()));
    }
  }

  @Test
  void sessionPermissionDoesNotProduceSuccessWithoutPersistedBooking() throws Exception {
    var session = new MockHttpSession();
    confirmationAccess.grant(session, "T3L-0000000000000000");
    mvc.perform(get("/reservations/confirmation").param("code", "T3L-0000000000000000").session(session))
        .andExpect(status().isNotFound())
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("reservation-details"))));
  }

  @Test
  void ownerReceiptEscapesGuestSuppliedContent() throws Exception {
    var form = form(LocalTime.of(18, 0), 2, "");
    form.setNote("<script>alert('private')</script>\nGhế trẻ em");
    Reservation saved = service.create(form);
    createdIds.add(saved.getId());
    var owner = new MockHttpSession();
    confirmationAccess.grant(owner, saved.getCode());
    mvc.perform(get("/reservations/confirmation").param("code", saved.getCode()).session(owner))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>alert('private')</script>"))));
  }
}
