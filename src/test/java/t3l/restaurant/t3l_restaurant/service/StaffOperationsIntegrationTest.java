package t3l.restaurant.t3l_restaurant.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import t3l.restaurant.t3l_restaurant.dto.ReservationCancellationForm;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.dto.ReservationUpdateForm;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.ReservationEmail;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:staff_operations;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.open-in-view=false", "spring.sql.init.mode=never", "app.google.client-id=", "app.google.client-secret=",
    "spring.mail.username=", "spring.mail.password=",
    "spring.data.mongodb.uri=mongodb://127.0.0.1:27099/t3lStaffTest?serverSelectionTimeoutMS=200",
    "app.jwt.secret=Y3VzdG9tZXItYWNjb3VudHMtdGVzdC1rZXktdGhpcy1pcy1mb3ItdGVzdHMtb25seQ==",
    "app.staff.username=staff", "app.admin.username=admin"
})
@AutoConfigureMockMvc
class StaffOperationsIntegrationTest {
  private static final String STAFF = "staff";
  private static final LocalDate TODAY = LocalDate.of(2032, 2, 13);
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired TestClock clock;
  @Autowired ReservationRepository reservations;
  @Autowired ReservationService bookings;
  @Autowired ReservationManagementService changes;
  @Autowired UserAccountRepository accounts;
  @Autowired PreorderService preorders;
  private Long tableOne;
  private Long tableTwo;
  private Long smallTable;
  private Long dishId;

  @TestConfiguration
  static class FixedSchedule {
    @Bean TestClock staffTestClock() { return new TestClock(); }
    @Bean @Primary ReservationSchedule testSchedule(TestClock clock) {
      return new ReservationSchedule(LocalTime.of(10, 0), LocalTime.of(22, 0), 120, 30, clock);
    }
  }

  static class TestClock extends Clock {
    private final ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
    private volatile Instant instant;
    void at(LocalDateTime time) { instant = time.atZone(zone).toInstant(); }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
    @Override public Instant instant() { return instant; }
  }

  @BeforeEach
  void prepare() {
    clock.at(TODAY.atTime(11, 0));
    for (String table : List.of("dining_payments", "dining_order_items", "dining_orders",
        "reservation_events", "reservation_emails", "reservation_change_requests", "reservations",
        "cash_sales", "staff_shifts", "restaurant_tables", "dishes", "payment_reference_claims")) {
      jdbc.update("delete from " + table);
    }
    jdbc.update("insert into restaurant_tables(code,table_type,capacity,active) values "
        + "('S01','Sảnh chính',4,true),('S02','Sảnh chính',4,true),('V01','Phòng riêng',2,true)");
    jdbc.update("insert into dishes(name,description,price,category_name,available) values "
        + "('Bít tết','Bít tết nướng',250000,'Món chính',true)");
    tableOne = id("select id from restaurant_tables where code='S01'");
    tableTwo = id("select id from restaurant_tables where code='S02'");
    smallTable = id("select id from restaurant_tables where code='V01'");
    dishId = id("select id from dishes");
    accounts.findByUsername("staff-two").orElseGet(() -> accounts.save(new UserAccount("staff-two", "unused", "STAFF")));
  }

  @Test
  void guestBuyerStaffAndAdminShareAvailabilityForTheSameWindow() throws Exception {
    var guest = new MockHttpSession();
    String draft = (String) mvc.perform(get("/reservations/new").session(guest)
        .param("date", TODAY.toString()).param("time", "14:00").param("guests", "3").param("tableId", tableOne.toString()))
        .andExpect(view().name("web/table-plan")).andExpect(content().string(containsString("Yêu cầu đặt S01")))
        .andReturn().getModelAndView().getModel().get("bookingDraft");
    mvc.perform(post("/reservations").session(guest).with(csrf()).param("draft", draft)
        .param("guestName", "Khách kiểm thử sơ đồ").param("guestPhone", "0901234567").param("date", TODAY.toString())
        .param("time", "14:00").param("guests", "3").param("tableId", tableOne.toString()).param("consentAccepted", "true"))
        .andExpect(redirectedUrlPattern("/reservations/confirmation?code=*"));
    mvc.perform(post("/reservations").session(guest).with(csrf()).param("draft", draft)
        .param("guestName", "Khách kiểm thử sơ đồ").param("guestPhone", "0901234567").param("date", TODAY.toString())
        .param("time", "16:00").param("guests", "3").param("tableId", tableOne.toString()).param("consentAccepted", "true"))
        .andExpect(redirectedUrl("/reservations/new")).andExpect(flash().attributeExists("bookingNotice"));

    assertBoardState("15:30", "PENDING");
    assertBoardState("16:00", "FREE");
    var id = id("select id from reservations");
    step(id, "CONFIRMED").andExpect(flash().attributeExists("notice"));
    assertBoardState("15:30", "RESERVED");
    step(id, "CANCELLED").andExpect(flash().attributeExists("notice"));
    assertBoardState("15:30", "FREE");
  }

  private void assertBoardState(String time, String state) throws Exception {
    var customer = accounts.findByUsername("booking-board-buyer").orElseGet(() -> accounts.save(
        UserAccount.customer("Booking QA", null, "0911111111", "booking-board-buyer", "unused")));
    for (boolean signedIn : List.of(false, true)) {
      var request = get("/reservations/new").param("date", TODAY.toString()).param("time", time).param("guests", "3");
      if (signedIn) request.with(user(customer.getUsername()).roles("BUYER"));
      mvc.perform(request).andExpect(status().isOk()).andExpect(view().name("web/table-plan"))
          .andExpect(content().string(not(containsString("Khách kiểm thử sơ đồ"))))
          .andExpect(result -> {
            var board = (List<?>) result.getModelAndView().getModel().get("tables");
            var cards = board.stream().map(PublicTableBoardService.TableView.class::cast).toList();
            assertEquals(state, cards.stream().filter(card -> card.table().getId().equals(tableOne)).findFirst().orElseThrow().state());
            assertEquals("TOO_SMALL", cards.stream().filter(card -> card.table().getId().equals(smallTable)).findFirst().orElseThrow().state());
          });
    }
    for (String role : List.of("staff", "admin")) {
      mvc.perform(get("/" + role + "/tables").with(user(role).roles(role.toUpperCase(java.util.Locale.ROOT)))
          .param("date", TODAY.toString()).param("time", time).param("guests", "3"))
          .andExpect(status().isOk()).andExpect(result -> {
            var board = (List<?>) result.getModelAndView().getModel().get("board");
            var cards = board.stream().map(StaffWorkspaceService.TableView.class::cast).toList();
            assertEquals(state, cards.stream().filter(card -> card.table().getId().equals(tableOne)).findFirst().orElseThrow().state().name());
          });
    }
  }

  @Test
  void seatedTableStaysBlockedDuringAnOverrunUntilServiceEnds() throws Exception {
    staff(post("/staff/shift/open").param("openingCash", "0")).andExpect(flash().attributeExists("notice"));
    long booking = walkIn("Khách ở lại lâu", tableOne);
    assertEquals(Reservation.SEATED, reservations.findOneById(booking).orElseThrow().getStatus());
    clock.at(TODAY.atTime(13, 5));
    assertBoardState("13:30", "OCCUPIED");
    assertBoardState("14:00", "FREE");
    step(booking, "COMPLETED").andExpect(flash().attributeExists("notice"));
    assertBoardState("13:30", "FREE");
  }

  @Test
  void approvingBuyerRescheduleStartsDepositCollectionAndKeepsPreorder() throws Exception {
    jdbc.update("update restaurant_tables set table_type='Phòng VIP' where id=?", tableOne);
    var customer = accounts.findByUsername("deposit-buyer").orElseGet(() -> accounts.save(
        UserAccount.customer("Khách VIP QA", "vip@example.test", "0922222222", "deposit-buyer", "unused")));
    var form = new ReservationForm();
    form.setDate(TODAY);
    form.setTime(LocalTime.of(14, 0));
    form.setGuests(3);
    form.setTableId(tableOne);
    form.setGuestName(customer.getFullName());
    form.setGuestPhone(customer.getPhone());
    form.setGuestEmail(customer.getEmail());
    form.setPreorderDishIds(List.of(dishId));
    form.setConsentAccepted(true);
    var booking = bookings.create(form, customer);
    assertEquals(new BigDecimal("500000"), booking.getDepositAmount());
    var change = new ReservationUpdateForm();
    change.setDate(TODAY);
    change.setTime(LocalTime.of(15, 0));
    change.setGuests(3);
    var requested = changes.requestChange(booking.getCode(), change, customer.getId());
    staff(post("/staff/reservations/requests/" + requested.getId() + "/review").param("decision", "APPROVE"))
        .andExpect(flash().attributeExists("notice"));
    var moved = reservations.findOneById(booking.getId()).orElseThrow();
    assertEquals(Reservation.CONFIRMED, moved.getStatus());
    assertEquals("WAITING_PAYMENT", moved.getDepositStatus());
    assertEquals(new BigDecimal("500000"), moved.getDepositAmount());
    assertEquals(ReservationEmail.AWAITING_DEPOSIT, jdbc.queryForObject(
        "select booking_status from reservation_emails where reservation_id=? and kind='RESCHEDULED'",
        String.class, booking.getId()));
    assertEquals(1L, id("select count(*) from dining_order_items"));
    step(booking.getId(), "CHECKED_IN").andExpect(flash().attributeExists("error"));
    mvc.perform(post("/reservations/" + booking.getCode() + "/deposit-reference")
        .with(user(customer.getUsername()).roles("BUYER")).with(csrf()).param("reference", "QA-TRANSFER"))
        .andExpect(flash().attributeExists("notice"));
    verifyDeposit(booking.getId(), "QA-TRANSFER", "500000", true)
        .andExpect(flash().attributeExists("notice"));
    clock.at(TODAY.atTime(14, 40));
    step(booking.getId(), "CHECKED_IN").andExpect(flash().attributeExists("notice"));
  }

  @Test
  void guestCannotBypassVipDepositAndLateTransferReleasesTableThenQueuesRefund() throws Exception {
    jdbc.update("update restaurant_tables set table_type='Phòng VIP' where id=?", tableOne);
    var session = new MockHttpSession();
    String receipt = mvc.perform(post("/reservations").session(session).with(csrf())
        .param("guestName", "Khách thử cọc").param("guestPhone", "0911111111")
        .param("tableId", tableOne.toString()).param("date", TODAY.toString()).param("time", "14:00")
        .param("guests", "3").param("consentAccepted", "true").param("depositAmount", "0"))
        .andExpect(redirectedUrlPattern("/reservations/confirmation?code=*")).andReturn().getResponse().getRedirectedUrl();
    long booking = id("select id from reservations");
    var saved = reservations.findOneById(booking).orElseThrow();
    assertEquals(new BigDecimal("500000"), saved.getDepositAmount());
    step(booking, "CONFIRMED").andExpect(flash().attributeExists("notice"));
    mvc.perform(get(receipt).session(session)).andExpect(status().isOk())
        .andExpect(content().string(containsString("500.000 đ"))).andExpect(content().string(containsString("Gửi mã giao dịch")));
    var transfer = "/reservations/" + saved.getCode() + "/deposit-reference";
    mvc.perform(post(transfer).with(csrf()).param("reference", "QA-LATE-CREDIT")).andExpect(status().isNotFound());
    mvc.perform(post(transfer).session(session).with(csrf()).param("reference", " qa-late-credit "))
        .andExpect(flash().attributeExists("notice"));
    verifyDeposit(booking, "QA-LATE-CREDIT", "1", true).andExpect(flash().attributeExists("error"));
    clock.at(TODAY.atTime(13, 0));
    assertBoardState("14:00", "FREE");
    bookings.releaseExpiredDeposit(booking);
    saved = reservations.findOneById(booking).orElseThrow();
    assertEquals(Reservation.CANCELLED, saved.getStatus());
    assertEquals("CLOSED_TRANSFER_PENDING", saved.getDepositStatus());
    verifyDeposit(booking, "QA-LATE-CREDIT", "500000", true).andExpect(flash().attributeExists("notice"));
    assertEquals(new BigDecimal("500000"), reservations.findOneById(booking).orElseThrow().getRefundRemaining());
    staff(post("/staff/reservations/" + booking + "/deposit/refund").param("amount", "500000").param("reference", "QA-LATE-REFUND"))
        .andExpect(flash().attributeExists("notice"));
    assertEquals("REFUNDED", reservations.findOneById(booking).orElseThrow().getDepositStatus());
    assertBoardState("14:00", "FREE");
  }

  @Test
  void confirmedDepositIsAppliedOnceAndOnlyBalanceEntersTheCashShift() throws Exception {
    jdbc.update("update restaurant_tables set table_type='Phòng VIP' where id=?", tableOne);
    long booking = depositGuest(tableOne, "14:00", "QA-CASH-SPLIT");
    clock.at(TODAY.atTime(13, 45));
    staff(post("/staff/shift/open").param("openingCash", "0")).andExpect(flash().attributeExists("notice"));
    step(booking, "CHECKED_IN").andExpect(flash().attributeExists("notice"));
    step(booking, "SEATED").andExpect(flash().attributeExists("notice"));
    String order = "/staff/orders/" + booking;
    staff(post(order + "/open")).andExpect(flash().attributeExists("notice"));
    staff(post(order + "/items").param("dishId", dishId.toString()).param("quantity", "3"))
        .andExpect(flash().attributeExists("notice"));
    staff(post(order + "/finalize")).andExpect(flash().attributeExists("notice"));
    staff(get(order)).andExpect(status().isOk()).andExpect(content().string(containsString("250.000 đ")));
    staff(post(order + "/pay").param("method", "TRANSFER").param("received", "true").param("reference", "qa-cash-split"))
        .andExpect(flash().attributeExists("error"));
    payCash(order).andExpect(flash().attributeExists("notice"));
    payCash(order).andExpect(flash().attributeExists("notice"));
    assertEquals(1L, id("select count(*) from dining_payments"));
    assertEquals(250000L, id("select amount from dining_payments"));
    assertEquals(500000L, id("select deposit_applied from dining_payments"));
    mvc.perform(get("/admin/revenue").with(user("admin").roles("ADMIN")).param("from", TODAY.toString()).param("to", TODAY.toString()))
        .andExpect(status().isOk()).andExpect(content().string(containsString("750.000 đ")));
    staff(post("/staff/shift/close").param("countedCash", "250000")).andExpect(flash().attributeExists("notice"));
    assertEquals(250000L, id("select cash_sales from staff_shifts"));
  }

  @Test
  void depositCanCoverEntireBillAndExcessIsRefunded() throws Exception {
    jdbc.update("update restaurant_tables set table_type='Phòng VIP' where id=?", tableOne);
    long booking = depositGuest(tableOne, "14:00", "QA-EXCESS-CREDIT");
    clock.at(TODAY.atTime(13, 45));
    staff(post("/staff/shift/open").param("openingCash", "0")).andExpect(flash().attributeExists("notice"));
    step(booking, "CHECKED_IN").andExpect(flash().attributeExists("notice"));
    step(booking, "SEATED").andExpect(flash().attributeExists("notice"));
    String order = "/staff/orders/" + booking;
    staff(post(order + "/open")).andExpect(flash().attributeExists("notice"));
    staff(post(order + "/items").param("dishId", dishId.toString()).param("quantity", "1")).andExpect(flash().attributeExists("notice"));
    staff(post(order + "/finalize")).andExpect(flash().attributeExists("notice"));
    payCash(order).andExpect(flash().attributeExists("error"));
    staff(post(order + "/pay").param("method", "DEPOSIT").param("received", "true")).andExpect(flash().attributeExists("notice"));
    assertEquals(0L, id("select amount from dining_payments"));
    assertEquals(250000L, id("select deposit_applied from dining_payments"));
    assertEquals(new BigDecimal("250000"), reservations.findOneById(booking).orElseThrow().getRefundRemaining());
    staff(post("/staff/reservations/" + booking + "/deposit/refund").param("amount", "250000").param("reference", "QA-EXCESS-REFUND"))
        .andExpect(flash().attributeExists("notice"));
    assertEquals("REFUNDED", reservations.findOneById(booking).orElseThrow().getDepositStatus());
  }

  @Test
  void customerCancellationRetainsOnlyTheConfiguredShare() throws Exception {
    jdbc.update("update restaurant_tables set table_type='Phòng VIP' where id=?", tableOne);
    long booking = depositGuest(tableOne, "14:00", "QA-CANCEL-CREDIT");
    var cancel = new ReservationCancellationForm();
    cancel.setReason("Đổi kế hoạch");
    var requested = changes.requestCancellation(reservations.findOneById(booking).orElseThrow().getCode(), cancel);
    staff(post("/staff/reservations/requests/" + requested.getId() + "/review").param("decision", "APPROVE"))
        .andExpect(flash().attributeExists("notice"));
    var saved = reservations.findOneById(booking).orElseThrow();
    assertEquals("REFUND_PENDING", saved.getDepositStatus());
    assertEquals(new BigDecimal("250000"), saved.getRefundRemaining());
    assertEquals(new BigDecimal("250000"), saved.getDepositRetained());
  }

  @Test
  void reschedulingToVipUsesExistingCreditAndNoShowClosesDepositOnlyOnce() throws Exception {
    jdbc.update("update restaurant_tables set table_type='Bàn lớn' where id=?", tableOne);
    jdbc.update("update restaurant_tables set table_type='Phòng VIP' where id=?", tableTwo);
    long booking = createPhone("Khách đổi bàn QA", "14:00", tableOne, 3);
    bookings.submitDepositReference(booking, null, "QA-INITIAL-CREDIT");
    verifyDeposit(booking, "QA-INITIAL-CREDIT", "300000", true).andExpect(flash().attributeExists("notice"));
    staff(post("/staff/reservations/" + booking + "/table").param("tableId", tableTwo.toString()))
        .andExpect(flash().attributeExists("error"));
    staff(post("/staff/reservations/" + booking + "/reschedule").param("tableId", tableTwo.toString())
        .param("date", TODAY.toString()).param("time", "14:00").param("guests", "3"))
        .andExpect(flash().attributeExists("notice"));
    var moved = reservations.findOneById(booking).orElseThrow();
    assertEquals(new BigDecimal("200000"), moved.getDepositRemaining());
    assertThrows(BookingException.class, () -> bookings.submitDepositReference(booking, null, "qa-initial-credit"));
    bookings.submitDepositReference(booking, null, "QA-TOPUP-CREDIT");
    verifyDeposit(booking, "QA-INITIAL-CREDIT", "200000", true).andExpect(flash().attributeExists("error"));
    verifyDeposit(booking, "QA-TOPUP-CREDIT", "200000", true).andExpect(flash().attributeExists("notice"));
    clock.at(TODAY.atTime(14, 20));
    step(booking, "NO_SHOW").andExpect(flash().attributeExists("notice"));
    step(booking, "NO_SHOW").andExpect(flash().attributeExists("notice"));
    var closed = reservations.findOneById(booking).orElseThrow();
    assertEquals("FORFEITED", closed.getDepositStatus());
    assertEquals(new BigDecimal("500000"), closed.getDepositRetained());
    assertEquals(BigDecimal.ZERO, closed.getRefundRemaining());
  }

  @Test
  void buyerRequestsMorePortionsOldPricesAndCreditAreKeptAndServingReusesTheOrder() throws Exception {
    var buyer = preorderBuyer();
    var other = accounts.findByUsername("other-preorder-buyer").orElseGet(() -> accounts.save(
        UserAccount.customer("Khách khác", null, "0923456789", "other-preorder-buyer", "unused")));
    var booking = preorderBooking(buyer, "14:00", 2);
    long id = booking.getId();
    String code = booking.getCode();
    String page = "/account/reservations/" + code + "/preorder";
    long orderId = preorders.order(id).getId();
    assertEquals(new BigDecimal("350000"), booking.getDepositAmount());
    step(id, "CONFIRMED").andExpect(flash().attributeExists("notice"));
    bookings.submitDepositReference(id, buyer.getId(), "QA-PREORDER-BASE");
    verifyDeposit(id, "QA-PREORDER-BASE", "350000", true).andExpect(flash().attributeExists("notice"));

    jdbc.update("update dishes set price=300000 where id=?", dishId);
    mvc.perform(get(page).with(user(buyer.getUsername()).roles("BUYER")))
        .andExpect(status().isOk()).andExpect(content().string(containsString("Để sốt riêng")));
    mvc.perform(get(page).with(user(other.getUsername()).roles("BUYER"))).andExpect(status().isNotFound());
    mvc.perform(post(page).with(user(buyer.getUsername()).roles("BUYER")).with(csrf())
        .param("preorderQuantities[" + dishId + "]", "3").param("preorderNote", "Không hành")
        .param("preorderExpectedTotal", "800000")).andExpect(redirectedUrl(page));
    long requestId = id("select id from reservation_change_requests");
    staff(get("/staff/reservations")).andExpect(status().isOk())
        .andExpect(content().string(containsString("Giỏ món đề nghị")));
    staff(post("/staff/reservations/requests/" + requestId + "/review").param("decision", "APPROVE"))
        .andExpect(flash().attributeExists("notice"));

    var adjusted = reservations.findOneById(id).orElseThrow();
    assertEquals(new BigDecimal("560000"), adjusted.getDepositAmount());
    assertEquals(new BigDecimal("210000"), adjusted.getDepositRemaining());
    var order = preorders.order(id);
    assertEquals(orderId, order.getId());
    assertEquals(new BigDecimal("800000"), order.getPreorderTotal());
    assertEquals(List.of(new BigDecimal("250000"), new BigDecimal("300000")),
        order.getPreorderItems().stream().map(item -> item.getUnitPrice()).toList());
    assertEquals("Không hành", order.getPreorderNote());
    bookings.submitDepositReference(id, buyer.getId(), "QA-PREORDER-EXTRA");
    verifyDeposit(id, "QA-PREORDER-EXTRA", "210000", true).andExpect(flash().attributeExists("notice"));

    clock.at(TODAY.atTime(13, 45));
    staff(post("/staff/shift/open").param("openingCash", "0")).andExpect(flash().attributeExists("notice"));
    step(id, "CHECKED_IN").andExpect(flash().attributeExists("notice"));
    step(id, "SEATED").andExpect(flash().attributeExists("notice"));
    staff(post("/staff/orders/" + id + "/open")).andExpect(flash().attributeExists("notice"));
    staff(post("/staff/orders/" + id + "/finalize")).andExpect(flash().attributeExists("notice"));
    payCash("/staff/orders/" + id).andExpect(flash().attributeExists("notice"));
    assertEquals(1L, id("select count(*) from dining_orders"));
    assertEquals(800000L, id("select total_amount from dining_orders"));
    assertEquals(240000L, id("select amount from dining_payments"));
  }

  @Test
  void rejectsInvalidCartAndStalePriceAndDoesNotApplyAnUnavailableProposedDish() throws Exception {
    var buyer = preorderBuyer();
    var form = preorderForm("14:00", 21);
    assertThrows(BookingException.class, () -> bookings.create(form, buyer));
    form.setPreorderQuantities(Map.of(dishId, 2));
    form.setPreorderExpectedTotal(new BigDecimal("1"));
    assertThrows(BookingException.class, () -> bookings.create(form, buyer));
    form.setPreorderExpectedTotal(null);
    var booking = bookings.create(form, buyer);
    form.setPreorderQuantities(Map.of(dishId, 3));
    var request = changes.requestPreorder(booking.getCode(), buyer.getId(), form);
    jdbc.update("update dishes set price=300000 where id=?", dishId);
    staff(post("/staff/reservations/requests/" + request.getId() + "/review").param("decision", "APPROVE"))
        .andExpect(flash().attributeExists("error"));
    jdbc.update("update dishes set available=false where id=?", dishId);
    mvc.perform(get("/admin/reservations/" + booking.getId()).with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk()).andExpect(content().string(containsString("Hết hoặc ngừng bán")));
    staff(post("/staff/reservations/" + booking.getId() + "/preorder/unavailable"))
        .andExpect(flash().attributeExists("notice"));
    assertEquals(BigDecimal.ZERO, preorders.order(booking.getId()).getPreorderTotal());
    assertEquals(BigDecimal.ZERO, reservations.findOneById(booking.getId()).orElseThrow().getDepositAmount());
    staff(post("/staff/reservations/requests/" + request.getId() + "/review").param("decision", "APPROVE"))
        .andExpect(flash().attributeExists("error"));
  }

  private UserAccount preorderBuyer() {
    return accounts.findByUsername("preorder-buyer").orElseGet(() -> accounts.save(
        UserAccount.customer("Khách đặt món QA", "preorder@example.test", "0912345678", "preorder-buyer", "unused")));
  }

  @Test
  void cancellingOrMissingReservationClosesPreorderWithoutBillingIt() throws Exception {
    var buyer = preorderBuyer();
    var cancelled = preorderBooking(buyer, "12:00", 2);
    step(cancelled.getId(), "CANCELLED").andExpect(flash().attributeExists("notice"));
    var missed = preorderBooking(buyer, "14:00", 2);
    step(missed.getId(), "CONFIRMED").andExpect(flash().attributeExists("notice"));
    bookings.submitDepositReference(missed.getId(), buyer.getId(), "QA-PREORDER-NOSHOW");
    verifyDeposit(missed.getId(), "QA-PREORDER-NOSHOW", "350000", true)
        .andExpect(flash().attributeExists("notice"));
    clock.at(TODAY.atTime(14, 30));
    step(missed.getId(), "NO_SHOW").andExpect(flash().attributeExists("notice"));
    assertEquals("VOID", preorders.order(cancelled.getId()).getStatus());
    assertEquals("VOID", preorders.order(missed.getId()).getStatus());
    assertEquals(new BigDecimal("500000"), preorders.order(missed.getId()).getPreorderTotal());
    assertEquals(new BigDecimal("350000"), reservations.findOneById(missed.getId()).orElseThrow().getDepositRetained());
    assertEquals(0L, id("select count(*) from dining_payments"));
  }

  private Reservation preorderBooking(UserAccount buyer, String time, int quantity) {
    return bookings.create(preorderForm(time, quantity), buyer);
  }

  private ReservationForm preorderForm(String time, int quantity) {
    var form = new ReservationForm();
    form.setGuestName("Khách đặt món QA");
    form.setGuestPhone("0912345678");
    form.setDate(TODAY);
    form.setTime(LocalTime.parse(time));
    form.setTableId(tableOne);
    form.setGuests(3);
    form.setConsentAccepted(true);
    form.setPreorderQuantities(Map.of(dishId, quantity));
    form.setPreorderNote("Để sốt riêng");
    return form;
  }

  private long depositGuest(Long table, String time, String reference) throws Exception {
    long booking = createPhone("Khách trả cọc QA", time, table, 3);
    bookings.submitDepositReference(booking, null, reference);
    verifyDeposit(booking, reference, "500000", true).andExpect(flash().attributeExists("notice"));
    return booking;
  }

  private ResultActions verifyDeposit(long booking, String reference, String amount, boolean received) throws Exception {
    return staff(post("/staff/reservations/" + booking + "/deposit").param("received", Boolean.toString(received))
        .param("amount", amount).param("reference", reference));
  }

  @Test
  void booksChecksInSeatsMovesOrdersCollectsOnceAndReconcilesWithExistingSales() throws Exception {
    staff(post("/staff/shift/open").param("openingCash", "200000")).andExpect(flash().attributeExists("notice"));
    long booking = createPhone("Nguyễn Thu Linh", "11:30", tableOne, 3);
    String detail = "/staff/reservations/" + booking;
    String order = "/staff/orders/" + booking;
    staff(get("/staff/reservations").param("date", TODAY.toString()).param("name", "Thu Linh")
        .param("phone", "090 123 4567").param("status", "CONFIRMED"))
        .andExpect(status().isOk()).andExpect(content().string(containsString("Nguyễn Thu Linh")));
    staff(get(detail)).andExpect(status().isOk()).andExpect(content().string(containsString("Lịch sử xử lý")));
    step(booking, "SEATED").andExpect(flash().attributeExists("error"));
    step(booking, "RECEIVE").andExpect(flash().attributeExists("notice"));
    var seated = reservations.findOneById(booking).orElseThrow();
    assertEquals("SEATED", seated.getStatus());
    assertEquals(seated.getCheckedInAt(), seated.getSeatedAt());
    clock.at(TODAY.atTime(11, 4, 30));
    staff(get(detail)).andExpect(content().string(containsString("data-service-seconds=\"270\"")));
    staff(get("/staff/tables")).andExpect(status().isOk()).andExpect(content().string(containsString("Đang phục vụ")));
    staff(get("/staff/orders")).andExpect(status().isOk()).andExpect(content().string(containsString("S01")));

    staff(post(order + "/open")).andExpect(flash().attributeExists("notice"));
    staff(post(order + "/open")).andExpect(flash().attributeExists("notice"));
    staff(post(order + "/finalize")).andExpect(flash().attributeExists("error"));
    staff(post(order + "/items").param("dishId", dishId.toString()).param("quantity", "1"))
        .andExpect(flash().attributeExists("notice"));
    var item = id("select min(id) from dining_order_items");
    staff(post(order + "/items/" + item).param("quantity", "2")).andExpect(flash().attributeExists("notice"));
    staff(post(order + "/items").param("dishId", dishId.toString()).param("quantity", "1"))
        .andExpect(flash().attributeExists("notice"));
    staff(post(order + "/items/" + id("select max(id) from dining_order_items")).param("cancel", "true"))
        .andExpect(flash().attributeExists("notice"));
    jdbc.update("update dishes set price=300000 where id=?", dishId);
    staff(post(detail + "/table").param("tableId", smallTable.toString())).andExpect(flash().attributeExists("error"));
    jdbc.update("insert into restaurant_tables(code,table_type,capacity,active) values ('M01','Bàn vừa',8,true)");
    var otherKind = id("select id from restaurant_tables where code='M01'");
    staff(get(detail).param("move", "true")).andExpect(content().string(containsString("id=\"move-table\"")));
    staff(post(detail + "/table").param("tableId", otherKind.toString())).andExpect(flash().attributeExists("error"));
    staff(post(detail + "/table").param("tableId", tableTwo.toString())).andExpect(flash().attributeExists("notice"));
    assertEquals(tableTwo, reservations.findOneById(booking).orElseThrow().getTable().getId());
    staff(get(order)).andExpect(status().isOk()).andExpect(content().string(containsString("500.000 đ")));
    staff(post(order + "/finalize")).andExpect(flash().attributeExists("notice"));
    staff(post(order + "/items/" + item).param("quantity", "3")).andExpect(flash().attributeExists("error"));
    staff(get(order)).andExpect(status().isOk()).andExpect(content().string(containsString("Xác nhận thu tiền")));
    step(booking, "COMPLETED").andExpect(flash().attributeExists("error"));
    staff(post(order + "/pay").param("method", "CASH")).andExpect(flash().attributeExists("error"));
    assertEquals(0L, id("select count(*) from dining_payments"));

    var go = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> { go.await(); payCash(order).andExpect(flash().attributeExists("notice")); return true; });
      var second = executor.submit(() -> { go.await(); payCash(order).andExpect(flash().attributeExists("notice")); return true; });
      go.countDown();
      assertTrue(first.get(20, TimeUnit.SECONDS));
      assertTrue(second.get(20, TimeUnit.SECONDS));
    }
    assertEquals(1L, id("select count(*) from dining_payments"));
    staff(get(order)).andExpect(status().isOk()).andExpect(content().string(containsString("Thông tin thanh toán")));
    assertEquals("COMPLETED", reservations.findOneById(booking).orElseThrow().getStatus());
    step(booking, "COMPLETED").andExpect(flash().attributeExists("notice"));

    staff(post("/staff/shift/sales").param("dishId", dishId.toString()).param("quantity", "1")
        .param("paymentMethod", "TRANSFER").param("received", "true")).andExpect(flash().attributeExists("notice"));
    staff(get("/staff/shift")).andExpect(status().isOk()).andExpect(content().string(containsString("800.000 đ")));
    staff(post("/staff/shift/close").param("countedCash", "695000")).andExpect(flash().attributeExists("notice"));
    var shift = id("select id from staff_shifts");
    staff(get("/staff/shift").param("id", shift.toString())).andExpect(status().isOk())
        .andExpect(content().string(containsString("695.000 đ")));
    assertEquals(new BigDecimal("-5000"), jdbc.queryForObject("select cash_difference from staff_shifts", BigDecimal.class));
    assertEquals(new BigDecimal("500000"), jdbc.queryForObject("select cash_sales from staff_shifts", BigDecimal.class));
    assertEquals(new BigDecimal("300000"), jdbc.queryForObject("select other_sales from staff_shifts", BigDecimal.class));
    assertEquals(3L, id("select sum(guest_count) from reservations where service_shift_id=" + shift));
  }

  @Test
  void protectsTableCapacityAndOverlapsAndProcessesExistingChangeAndEmailQueue() throws Exception {
    staff(phone("Khách ba người", "14:00", smallTable, 3)).andExpect(status().isOk())
        .andExpect(content().string(containsString("Bàn không đủ chỗ")));
    long first = createPhone("Hà An", "14:00", tableOne, 3);
    staff(phone("Trùng lịch", "15:30", tableOne, 3)).andExpect(status().isOk())
        .andExpect(content().string(containsString("lịch trùng")));
    createPhone("Lượt tiếp theo", "16:00", tableOne, 3);
    staff(post("/staff/tables/" + tableOne + "/availability").param("active", "false"))
        .andExpect(flash().attributeExists("error"));
    staff(post("/staff/tables/" + smallTable + "/availability").param("active", "false"))
        .andExpect(flash().attributeExists("notice"));
    staff(get("/staff/tables")).andExpect(status().isOk())
        .andExpect(content().string(containsString("data-state=\"INACTIVE\"")))
        .andExpect(content().string(containsString("data-state=\"FREE\"")))
        .andExpect(content().string(containsString("Mở lại bàn")));
    staff(post("/staff/tables/" + smallTable + "/availability").param("active", "true"))
        .andExpect(flash().attributeExists("notice"));

    var changed = new ReservationUpdateForm();
    changed.setDate(TODAY.plusDays(1));
    changed.setTime(LocalTime.of(17, 0));
    changed.setGuests(3);
    var request = changes.requestChange(reservations.findOneById(first).orElseThrow().getCode(), changed);
    staff(get("/staff/reservations")).andExpect(status().isOk()).andExpect(content().string(containsString("Duyệt yêu cầu")));
    staff(post("/staff/reservations/requests/" + request.getId() + "/review").param("decision", "APPROVE"))
        .andExpect(flash().attributeExists("notice"));
    assertEquals(TODAY.plusDays(1), reservations.findOneById(first).orElseThrow().getReservationDate());
    staff(post("/staff/reservations/" + first + "/reschedule").param("date", TODAY.plusDays(1).toString())
        .param("time", "18:00").param("guests", "4").param("tableId", tableTwo.toString()))
        .andExpect(flash().attributeExists("notice"));
    staff(get("/staff/reservations/" + first)).andExpect(status().isOk())
        .andExpect(content().string(containsString("Đổi lịch:"))).andExpect(content().string(containsString("Đã duyệt")));
    var cancel = new ReservationCancellationForm();
    cancel.setReason("Dời kế hoạch.");
    var cancelRequest = changes.requestCancellation(reservations.findOneById(first).orElseThrow().getCode(), cancel);
    staff(post("/staff/reservations/requests/" + cancelRequest.getId() + "/review").param("decision", "REJECT"))
        .andExpect(flash().attributeExists("error"));
    staff(post("/staff/reservations/requests/" + cancelRequest.getId() + "/review").param("decision", "REJECT")
        .param("reviewNote", "Đã trao đổi với khách.")).andExpect(flash().attributeExists("notice"));
    var another = changes.requestCancellation(reservations.findOneById(first).orElseThrow().getCode(), cancel);
    staff(post("/staff/reservations/requests/" + another.getId() + "/review").param("decision", "APPROVE"))
        .andExpect(flash().attributeExists("notice"));
    assertEquals("CANCELLED", reservations.findOneById(first).orElseThrow().getStatus());
    assertEquals(List.of("CONFIRMED", "RESCHEDULED", "RESCHEDULED", "CANCELLATION_DECLINED", "CANCELLATION_APPROVED"),
        jdbc.queryForList("select kind from reservation_emails where reservation_id=? order by id", String.class, first));
  }

  @Test
  void staffAddsMenuItemsToTheInvoiceForBothWaitingAndWalkInBookings() throws Exception {
    staff(post("/staff/shift/open").param("openingCash", "0")).andExpect(flash().attributeExists("notice"));
    long reserved = createPhone("Khách đặt bàn chờ", "18:00", tableOne, 2);
    long arrived = walkIn("Khách đến ngay", tableTwo);
    for (long id : List.of(reserved, arrived)) {
      String code = reservations.findOneById(id).orElseThrow().getCode();
      String details = "/staff/reservations/" + id;
      String preorder = "/reservations/" + code + "/preorder";
      staff(get(details)).andExpect(content().string(containsString("/menu?booking=" + code)));
      staff(get("/menu").param("booking", code)).andExpect(content().string(containsString(preorder)));
      staff(post(preorder).param("items[0].dishId", dishId.toString()).param("items[0].quantity", "2")
          .param("items[0].note", "Ít cay")).andExpect(redirectedUrl(details));
      staff(get("/staff/orders/" + id)).andExpect(status().isOk())
          .andExpect(content().string(containsString("Ghi chú: Ít cay")));
      staff(get(details)).andExpect(content().string(containsString("2 × Bít tết")));
    }
    assertEquals(2L, id("select count(distinct reservation_id) from dining_orders where status='DRAFT'"));
    assertEquals(2L, id("select count(*) from dining_order_items"));
  }

  @Test
  void handlesNoShowAndWalkInsAndRequiresRealTransferConfirmation() throws Exception {
    var missed = publicBooking("Khách đặt trực tuyến", "11:30");
    step(missed, "CONFIRMED").andExpect(flash().attributeExists("notice"));
    step(missed, "NO_SHOW").andExpect(flash().attributeExists("error"));
    clock.at(TODAY.atTime(11, 45));
    step(missed, "NO_SHOW").andExpect(flash().attributeExists("notice"));
    staff(get("/staff/reservations/" + missed)).andExpect(status().isOk())
        .andExpect(content().string(containsString("Khách không đến")));
    assertEquals(List.of("RECEIVED", "CONFIRMED", "NO_SHOW"),
        jdbc.queryForList("select kind from reservation_emails where reservation_id=? order by id", String.class, missed));
    var rejected = publicBooking("Khách chờ xác nhận", "16:30");
    staff(get("/staff/tables")).andExpect(content().string(containsString("Đợi xác nhận")))
        .andExpect(content().string(containsString("table-board-confirm")))
        .andExpect(content().string(not(containsString("Sửa thông tin bàn"))));
    mvc.perform(get("/admin/tables").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk()).andExpect(content().string(containsString("Sửa thông tin bàn")));
    step(rejected, "CANCELLED").andExpect(flash().attributeExists("notice"));
    assertEquals(List.of("RECEIVED", "REJECTED"),
        jdbc.queryForList("select kind from reservation_emails where reservation_id=? order by id", String.class, rejected));

    staff(get("/staff/reservations/new").param("walkIn", "true")).andExpect(status().isOk())
        .andExpect(content().string(containsString("Khách đến trực tiếp")));
    staff(post("/staff/reservations").param("walkIn", "true").param("guestName", "Khách tại chỗ")
        .param("tableId", tableOne.toString()).param("guests", "3")).andExpect(status().isOk())
        .andExpect(content().string(containsString("Hãy mở ca làm việc")));
    assertEquals(0L, id("select count(*) from reservations where guest_name='Khách tại chỗ'"));
    staff(post("/staff/shift/open").param("openingCash", "0")).andExpect(flash().attributeExists("notice"));
    long first = walkIn("Khách tại chỗ", tableOne);
    assertEquals("SEATED", reservations.findOneById(first).orElseThrow().getStatus());
    staff(post("/staff/reservations").param("walkIn", "true").param("guestName", "Khách mới")
        .param("guests", "3").param("tableId", tableOne.toString())).andExpect(status().isOk())
        .andExpect(content().string(containsString("Bàn đang phục vụ")));
    long second = walkIn("Khách thanh toán sau", tableTwo);
    assertEquals("SEATED", reservations.findOneById(second).orElseThrow().getStatus());
    staff(post("/staff/reservations/" + second + "/table").param("tableId", tableOne.toString()))
        .andExpect(flash().attributeExists("error"));
    for (long booking : List.of(first, second)) {
      String order = "/staff/orders/" + booking;
      staff(post(order + "/open")).andExpect(flash().attributeExists("notice"));
      staff(post(order + "/items").param("dishId", dishId.toString()).param("quantity", "1"))
          .andExpect(flash().attributeExists("notice"));
      staff(post(order + "/finalize")).andExpect(flash().attributeExists("notice"));
    }
    String pay = "/staff/orders/" + first + "/pay";
    staff(post(pay).param("method", "TRANSFER").param("received", "true")).andExpect(flash().attributeExists("error"));
    staff(post(pay).param("method", "TRANSFER").param("received", "true").param("reference", " bank-009 "))
        .andExpect(flash().attributeExists("notice"));
    staff(post("/staff/orders/" + second + "/pay").param("method", "TRANSFER").param("received", "true")
        .param("reference", "BANK-009")).andExpect(flash().attributeExists("error"));
    assertEquals(1L, id("select count(*) from dining_payments"));
    assertEquals("BANK-009", jdbc.queryForObject("select transfer_reference from dining_payments", String.class));
  }

  @Test
  void limitsShiftReportsAndInternalMutationsToAuthorizedRolesAndOwners() throws Exception {
    for (String path : List.of("/staff/dashboard", "/staff/reservations", "/staff/tables", "/staff/orders", "/staff/shift")) {
      mvc.perform(get(path)).andExpect(redirectedUrlPattern("**/staff/login"));
      mvc.perform(get(path).with(user("buyer").roles("BUYER"))).andExpect(status().isForbidden());
      staff(get(path)).andExpect(status().isOk());
    }
    staff(get("/admin/dishes")).andExpect(status().isForbidden());
    mvc.perform(post("/staff/shift/open").with(user("buyer").roles("BUYER")).with(csrf()).param("openingCash", "0"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/staff/shift/open").with(user(STAFF).roles("STAFF")).param("openingCash", "0"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/staff/shift/open").with(user("staff-two").roles("STAFF")).with(csrf()).param("openingCash", "1234567"))
        .andExpect(flash().attributeExists("notice"));
    var other = id("select id from staff_shifts");
    staff(get("/staff/shift").param("id", other.toString())).andExpect(status().isNotFound());
    staff(get("/staff/shift")).andExpect(content().string(not(containsString("staff-two"))));
    mvc.perform(get("/staff/shift").param("id", other.toString()).with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk()).andExpect(content().string(containsString("1.234.567 đ")));
  }

  private ResultActions staff(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.with(user(STAFF).roles("STAFF")).with(csrf()));
  }

  private ResultActions step(long booking, String status) throws Exception {
    return staff(post("/staff/reservations/" + booking + "/status").param("status", status).param("from", "detail"))
        .andExpect(redirectedUrl("/staff/reservations/" + booking));
  }

  private ResultActions payCash(String order) throws Exception {
    return staff(post(order + "/pay").param("method", "CASH").param("received", "true"));
  }

  private MockHttpServletRequestBuilder phone(String guest, String time, Long tableId, int guests) {
    return post("/staff/reservations").param("guestName", guest).param("guestPhone", "+84 90 123 4567")
        .param("guestEmail", "guest@example.test").param("date", TODAY.toString()).param("time", time)
        .param("guests", Integer.toString(guests)).param("tableId", tableId.toString());
  }

  private long createPhone(String guest, String time, Long tableId, int guests) throws Exception {
    return bookingId(staff(phone(guest, time, tableId, guests)));
  }

  private long walkIn(String guest, Long tableId) throws Exception {
    return bookingId(staff(post("/staff/reservations").param("walkIn", "true").param("guestName", guest)
        .param("tableId", tableId.toString()).param("guests", "3")));
  }

  private long bookingId(ResultActions result) throws Exception {
    String location = result.andExpect(redirectedUrlPattern("/staff/reservations/*")).andReturn().getResponse().getRedirectedUrl();
    return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
  }

  private long publicBooking(String guest, String time) {
    var form = new ReservationForm();
    form.setGuestName(guest);
    form.setGuestPhone("0901234567");
    form.setGuestEmail("guest@example.test");
    form.setDate(TODAY);
    form.setTime(LocalTime.parse(time));
    form.setGuests(3);
    form.setConsentAccepted(true);
    return bookings.create(form).getId();
  }

  private Long id(String sql) { return jdbc.queryForObject(sql, Long.class); }
}
