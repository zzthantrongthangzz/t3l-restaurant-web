package t3l.restaurant.t3l_restaurant.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import t3l.restaurant.t3l_restaurant.dto.ReservationCancellationForm;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.dto.ReservationUpdateForm;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
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
        "cash_sales", "staff_shifts", "restaurant_tables", "dishes")) {
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
    staff(get("/staff/tables")).andExpect(status().isOk()).andExpect(content().string(containsString("Mở lại bàn")));
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
