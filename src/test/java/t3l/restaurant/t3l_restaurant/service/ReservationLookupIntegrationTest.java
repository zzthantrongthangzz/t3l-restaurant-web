package t3l.restaurant.t3l_restaurant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
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
import t3l.restaurant.t3l_restaurant.dto.*;
import t3l.restaurant.t3l_restaurant.entity.*;
import t3l.restaurant.t3l_restaurant.repository.*;

@SpringBootTest
@AutoConfigureMockMvc
class ReservationLookupIntegrationTest {
  @Autowired ReservationService bookings;
  @Autowired ReservationManagementService management;
  @Autowired ReservationRepository reservations;
  @Autowired ReservationChangeRequestRepository requests;
  @Autowired RestaurantTableRepository tables;
  @Autowired ReservationSchedule schedule;
  @Autowired TransactionTemplate transaction;
  @Autowired MockMvc mvc;
  private final Queue<Long> ids = new ConcurrentLinkedQueue<>();
  private LocalDate date;

  @BeforeEach void unusedDate() {
    date = LocalDate.of(2097, 1, 1);
    while (!reservations.findByReservationDateBetweenAndStatusNot(date, date.plusDays(1), "CANCELLED").isEmpty()) date = date.plusDays(2);
  }
  @AfterEach void cleanupOnlyOwnFixtures() {
    transaction.executeWithoutResult(status -> {
      requests.deleteByReservation_IdIn(List.copyOf(ids));
      requests.flush();
      reservations.deleteAllByIdInBatch(List.copyOf(ids));
    });
    ids.clear();
  }
  private Reservation book(LocalTime time) {
    var form = new ReservationForm();
    form.setDate(date); form.setTime(time); form.setGuests(2); form.setPreferredArea("");
    form.setGuestName("Lookup integration guest"); form.setGuestPhone("0901234567"); form.setConsentAccepted(true);
    var booking = bookings.create(form); ids.add(booking.getId()); return booking;
  }
  private ReservationUpdateForm change() {
    var form = new ReservationUpdateForm();
    form.setDate(date.plusDays(1)); form.setTime(LocalTime.of(18, 0)); form.setGuests(2); form.setPreferredArea("");
    form.setReason("Changed plans"); return form;
  }
  private ReservationCancellationForm cancellation() {
    var form = new ReservationCancellationForm(); form.setReason("Cancel plans"); return form;
  }
  private void occupy(RestaurantTable table, LocalDate day) {
    transaction.executeWithoutResult(status -> {
      var booking = new Reservation("QA-" + UUID.randomUUID().toString().substring(0, 16),
          "Lookup fixture", "0901234567", null, table, day, LocalTime.of(18, 0), 1, "Fixture", true);
      booking.setBookingDetails(day.atTime(20, 0), null);
      ids.add(reservations.saveAndFlush(booking).getId());
    });
  }
  private String csrf(MockHttpSession session) throws Exception {
    String html = mvc.perform(get("/reservations/lookup").session(session)).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    var match = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
    assertTrue(match.find()); return match.group(1);
  }
  private void verify(MockHttpSession session, String code) throws Exception {
    mvc.perform(post("/reservations/lookup").session(session).param("_csrf", csrf(session))
            .param("phone", "+84 901 234 567"))
        .andExpect(status().isOk()).andExpect(model().attributeExists("lookupResults"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString(code)));
  }
  @Test void lookupAcceptsPhoneWithoutOtpAndKeepsDetailSessionScoped() throws Exception {
    var booking = book(LocalTime.of(18, 0)); var session = new MockHttpSession();
    mvc.perform(get("/reservations/lookup").param("code", booking.getCode()).session(session))
        .andExpect(status().isOk()).andExpect(model().attributeDoesNotExist("reservation"));
    mvc.perform(post("/reservations/lookup").session(session).param("_csrf", csrf(session))
            .param("code", booking.getCode()).param("phone", "0900000000"))
        .andExpect(status().isOk()).andExpect(model().attributeDoesNotExist("reservation"))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(booking.getGuestName()))));
    verify(session, booking.getCode());
    mvc.perform(get("/reservations/lookup").param("code", booking.getCode()).session(session))
        .andExpect(status().isOk()).andExpect(model().attributeExists("reservation"))
        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    mvc.perform(get("/reservations/lookup").param("code", booking.getCode()).session(new MockHttpSession()))
        .andExpect(model().attributeDoesNotExist("reservation"));
  }
  @Test void changeIsOnlyAppliedAfterApprovalAndPreservesHistory() {
    var booking = book(LocalTime.of(18, 0)); var form = change(); form.setGuests(4); form.setPreferredArea("Phòng VIP");
    var request = management.requestChange(booking.getCode(), form);
    assertEquals(date, bookings.findByCode(booking.getCode()).getReservationDate());
    management.review(request.getId(), "APPROVE", "Agreed with guest", "test-staff");
    var updated = bookings.findByCode(booking.getCode());
    assertEquals(date.plusDays(1), updated.getReservationDate()); assertEquals(4, updated.getGuestCount());
    assertTrue(updated.getTable().getCapacity() >= 4); assertEquals("Phòng VIP", updated.getTable().getTableType());
    assertEquals("CONFIRMED", updated.getStatus()); assertEquals(booking.getCode(), updated.getCode());
    var history = management.history(booking.getId()).get(0);
    assertEquals(date, history.getOriginalDate()); assertEquals("APPROVED", history.getStatus());
    assertThrows(BookingException.class, () -> management.review(request.getId(), "APPROVE", "again", "test-staff"));
  }
  @Test void cancellationAndRejectionKeepOfficialBookingUntilApproved() {
    var booking = book(LocalTime.of(18, 0)); bookings.updateStatus(booking.getId(), "CONFIRMED");
    var request = management.requestCancellation(booking.getCode(), cancellation());
    assertEquals("CONFIRMED", bookings.findByCode(booking.getCode()).getStatus());
    assertThrows(BookingException.class, () -> management.review(request.getId(), "REJECT", "", "test-staff"));
    management.review(request.getId(), "REJECT", "Please contact us", "test-staff");
    assertEquals("CONFIRMED", bookings.findByCode(booking.getCode()).getStatus());
    var second = management.requestCancellation(booking.getCode(), cancellation());
    management.review(second.getId(), "APPROVE", "Cancelled", "test-staff");
    assertEquals("CANCELLED", bookings.findByCode(booking.getCode()).getStatus());
    assertThrows(BookingException.class, () -> management.requestChange(booking.getCode(), change()));
  }
  @Test void approvalRechecksAvailabilityAndRollsBackWithoutLosingOriginalSlot() {
    var booking = book(LocalTime.of(18, 0)); var request = management.requestChange(booking.getCode(), change());
    tables.findByActiveTrueOrderByCodeAsc().forEach(table -> occupy(table, date.plusDays(1)));
    assertThrows(BookingException.class, () -> management.review(request.getId(), "APPROVE", null, "test-staff"));
    assertEquals(date, bookings.findByCode(booking.getCode()).getReservationDate());
    assertEquals("PENDING", management.history(booking.getId()).get(0).getStatus());
  }
  @Test void validatesNewTimeCapacityAreaAndNoOpWithoutSavingRequest() {
    var booking = book(LocalTime.of(18, 0)); var form = change(); form.setTime(LocalTime.of(20, 30));
    assertThrows(BookingException.class, () -> management.requestChange(booking.getCode(), form));
    form.setTime(LocalTime.of(18, 0)); form.setGuests(20);
    assertThrows(BookingException.class, () -> management.requestChange(booking.getCode(), form));
    form.setGuests(2); form.setPreferredArea("Unknown area");
    assertThrows(BookingException.class, () -> management.requestChange(booking.getCode(), form));
    form.setPreferredArea(""); form.setDate(date);
    assertThrows(BookingException.class, () -> management.requestChange(booking.getCode(), form));
    assertTrue(management.history(booking.getId()).isEmpty());
  }
  @Test void concurrentDuplicateRequestsCreateOnlyOnePendingItem() throws Exception {
    var booking = book(LocalTime.of(18, 0)); var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var futures = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
        start.await();
        try { management.requestCancellation(booking.getCode(), cancellation()); return true; }
        catch (BookingException expected) { return false; }
      })).toList();
      start.countDown(); int successes = 0;
      for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) successes++;
      assertEquals(1, successes); assertEquals(1, management.history(booking.getId()).size());
    }
  }
  @Test void concurrentApprovalsCannotAllocateLastTableTwice() throws Exception {
    var first = book(LocalTime.of(10, 0)); var second = book(LocalTime.of(12, 0));
    var one = management.requestChange(first.getCode(), change()); var two = management.requestChange(second.getCode(), change());
    var active = tables.findByActiveTrueOrderByCodeAsc();
    Long remaining = active.stream().filter(t -> t.getCapacity() >= 2).findFirst().orElseThrow().getId();
    active.stream().filter(t -> !t.getId().equals(remaining)).forEach(t -> occupy(t, date.plusDays(1)));
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var futures = List.of(one, two).stream().map(item -> executor.submit(() -> {
        start.await();
        try { management.review(item.getId(), "APPROVE", null, "test-staff"); return true; }
        catch (BookingException expected) { return false; }
      })).toList();
      start.countDown(); int successes = 0;
      for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) successes++;
      assertEquals(1, successes);
      long applied = List.of(first, second).stream().map(b -> bookings.findByCode(b.getCode()))
          .filter(b -> b.getReservationDate().equals(date.plusDays(1))).count();
      assertEquals(1, applied);
    }
  }
  @Test void expiredRequestCannotBeApprovedButCanBeRejected() {
    var table = tables.findByActiveTrueOrderByCodeAsc().get(0);
    var start = LocalDateTime.now(schedule.getClock()).plusMinutes(60);
    var booking = new Reservation("QA-" + UUID.randomUUID().toString().substring(0, 16), "Late review fixture",
        "0901234567", null, table, start.toLocalDate(), start.toLocalTime(), 2, null, true);
    booking.setBookingDetails(start.plusHours(2), null);
    var saved = transaction.execute(s -> reservations.saveAndFlush(booking)); ids.add(saved.getId());
    var request = transaction.execute(s -> requests.saveAndFlush(new ReservationChangeRequest(saved, "CANCEL", null, null, start.minusHours(3))));
    assertThrows(BookingException.class, () -> management.review(request.getId(), "APPROVE", null, "test-staff"));
    assertThrows(BookingException.class, () -> management.requestCancellation(saved.getCode(), cancellation()));
    management.review(request.getId(), "REJECT", "Notice deadline passed", "test-staff");
    assertEquals("PENDING_CONFIRMATION", bookings.findByCode(saved.getCode()).getStatus());
  }
  @Test void httpFlowRequiresVerificationAndStaffAuthorization() throws Exception {
    var booking = book(LocalTime.of(18, 0)); var owner = new MockHttpSession(); var stranger = new MockHttpSession();
    mvc.perform(post("/reservations/lookup/" + booking.getCode() + "/cancel").session(stranger)
            .param("_csrf", csrf(stranger)).param("reason", "Unauthorized"))
        .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/reservations/lookup"));
    assertTrue(management.history(booking.getId()).isEmpty());
    verify(owner, booking.getCode());
    mvc.perform(post("/reservations/lookup/" + booking.getCode() + "/change").session(owner).param("_csrf", csrf(owner))
            .param("date", date.plusDays(1).toString()).param("time", "18:00").param("guests", "4").param("preferredArea", "Phòng VIP"))
        .andExpect(status().is3xxRedirection());
    mvc.perform(get("/reservations/lookup").param("code", booking.getCode()).session(owner)).andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Chờ nhân viên duyệt")));
    var request = management.history(booking.getId()).get(0);
    mvc.perform(post("/staff/reservations/requests/" + request.getId() + "/review").session(owner)
            .param("_csrf", csrf(owner)).param("decision", "APPROVE"))
        .andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
    var staff = new MockHttpSession(); String token = csrf(staff);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("test-staff", "", List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
    staff.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    mvc.perform(get("/staff/reservations").session(staff)).andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Lịch đề nghị")));
    mvc.perform(post("/staff/reservations/requests/" + request.getId() + "/review").session(staff)
            .param("_csrf", token).param("decision", "APPROVE").param("reviewNote", "Approved with guest"))
        .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/staff/reservations"));
    assertEquals(date.plusDays(1), bookings.findByCode(booking.getCode()).getReservationDate());
    mvc.perform(get("/reservations/lookup").param("code", booking.getCode()).session(owner))
        .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Đã duyệt")));
  }
  @Test void invalidChangeFormRedisplaysErrorsAndKeepsOriginalData() throws Exception {
    var booking = book(LocalTime.of(18, 0)); var owner = new MockHttpSession(); verify(owner, booking.getCode());
    mvc.perform(post("/reservations/lookup/" + booking.getCode() + "/change").session(owner).param("_csrf", csrf(owner))
            .param("date", date.plusDays(1).toString()).param("time", "20:30").param("guests", "2"))
        .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("changeForm", "time"));
    assertEquals(date, bookings.findByCode(booking.getCode()).getReservationDate());
  }
}
