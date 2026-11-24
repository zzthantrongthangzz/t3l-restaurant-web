package t3l.restaurant.t3l_restaurant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:admin_workspace;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.open-in-view=false", "spring.sql.init.mode=never", "app.google.client-id=", "app.google.client-secret=",
    "spring.mail.username=", "spring.mail.password=", "app.reservation-mail.worker-enabled=false",
    "spring.data.mongodb.uri=mongodb://127.0.0.1:27099/t3lAdminTest?serverSelectionTimeoutMS=200",
    "app.jwt.secret=Y3VzdG9tZXItYWNjb3VudHMtdGVzdC1rZXktdGhpcy1pcy1mb3ItdGVzdHMtb25seQ==",
    "app.admin.username=admin", "app.admin.password=AdminOnlyForTest2026!", "app.staff.username=staff"
})
@AutoConfigureMockMvc
class AdminWorkspaceIntegrationTest {
  private static final String PASSWORD = "Local-Pass-QA-123";
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ReservationService bookings;
  @Autowired ReservationSchedule schedule;
  @Autowired UserAccountRepository accounts;
  @Autowired ObjectMapper json;
  private long table;
  private long dish;

  @BeforeEach
  void fixtures() {
    for (String tableName : List.of("dining_payments", "dining_order_items", "dining_orders", "reservation_events",
        "reservation_emails", "reservation_change_requests", "reservations", "cash_sales", "staff_shifts",
        "restaurant_tables", "dishes", "restaurant_sections", "restaurant_settings", "admin_activity")) {
      jdbc.update("delete from " + tableName);
    }
    jdbc.update("delete from app_users where username in ('qa-staff','qa-admin')");
    jdbc.update("insert into restaurant_sections(kind,name,active) values ('MENU','Món chính',true),('AREA','Sảnh chính',true)");
    jdbc.update("insert into restaurant_tables(code,table_type,capacity,active) values ('A01','Sảnh chính',4,true)");
    table = id("select id from restaurant_tables");
    jdbc.update("insert into dishes(name,description,price,category_name,available,published) values ('Bít tết QA','Mô tả',200000,'Món chính',true,true)");
    dish = id("select id from dishes");
  }

  @Test
  void guardsAllAdminRoutesAndRendersWorkspaceWithCorrectLoginLanding() throws Exception {
    mvc.perform(post("/login").with(csrf()).param("username", "admin").param("password", "AdminOnlyForTest2026!"))
        .andExpect(redirectedUrl("/admin/dashboard"));
    for (String path : List.of("/admin", "/admin/dashboard", "/admin/accounts", "/admin/accounts/new",
        "/admin/inventory", "/admin/inventory/new", "/admin/dishes/new", "/admin/categories",
        "/admin/reservations", "/admin/reservations/new", "/admin/tables", "/admin/orders",
        "/admin/shift", "/admin/shifts", "/admin/revenue", "/admin/invoices", "/admin/contacts", "/admin/activity", "/admin/settings")) {
      mvc.perform(get(path).with(user("staff").roles("STAFF"))).andExpect(status().isForbidden());
      mvc.perform(get(path).with(user("buyer").roles("BUYER"))).andExpect(status().isForbidden());
      admin(get(path)).andExpect(status().isOk()).andExpect(content().string(containsString("Điều hướng quản trị")));
    }
    for (String path : List.of("/admin/accounts","/admin/inventory", "/admin/categories","/admin/settings","/admin/reservations/1/status")) {
      mvc.perform(post(path).with(user("staff").roles("STAFF")).with(csrf())).andExpect(status().isForbidden());
    }
    mvc.perform(post("/admin/accounts").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
    mvc.perform(get("/staff/reservations").with(user("staff").roles("STAFF")))
        .andExpect(status().isOk()).andExpect(content().string(containsString("Điều hướng nhân viên")));
    admin(get("/admin/dashboard").param("period", "week")).andExpect(status().isOk());
    admin(get("/admin/dashboard").param("period", "month")).andExpect(status().isOk());
    admin(get("/admin/dishes")).andExpect(redirectedUrl("/menu"));
  }

  @Test
  void editsDishesFromTheSharedMenuWithAdminPermissions() throws Exception {
    String editPath = "/admin/dishes/" + dish + "/edit";
    mvc.perform(get("/js/menu.js")).andExpect(status().isOk())
        .andExpect(content().string(containsString("filterCards")));
    mvc.perform(get("/js/theme.js")).andExpect(status().isOk())
        .andExpect(content().string(containsString("t3l-theme")));
    mvc.perform(get("/menu")).andExpect(status().isOk())
        .andExpect(content().string(not(containsString(editPath))));
    mvc.perform(get("/menu").with(user("staff").roles("STAFF"))).andExpect(status().isOk())
        .andExpect(content().string(not(containsString(editPath))));
    mvc.perform(get(editPath).with(user("staff").roles("STAFF"))).andExpect(status().isForbidden());
    admin(get("/admin/dashboard")).andExpect(content().string(containsString("href=\"/menu\">Thực đơn")));
    admin(get("/menu")).andExpect(content().string(containsString(editPath)))
        .andExpect(content().string(containsString("<small>Quản trị viên</small>")));
    admin(get(editPath)).andExpect(status().isOk())
        .andExpect(content().string(containsString("Tải ảnh lên Cloudinary")));

    admin(post("/admin/dishes/" + dish).param("name", "Bít tết đã cập nhật").param("description", "Món thử")
        .param("price", "250000").param("categoryName", "Món chính").param("available", "true").param("_published", "on"))
        .andExpect(redirectedUrl("/menu"));
    admin(get("/menu")).andExpect(content().string(containsString("Bít tết đã cập nhật")))
        .andExpect(content().string(containsString("Đang ẩn")));
    mvc.perform(get("/menu")).andExpect(content().string(not(containsString("Bít tết đã cập nhật"))));
  }

  @Test
  void attachesMenuItemsOnlyAfterBookingAndKeepsGuestRequestsTableOnly() throws Exception {
    var customer = accounts.save(UserAccount.customer("Khách đặt món", null, "0901234567", "qa-preorder", "unused"));
    var session = new MockHttpSession();
    var buyer = user(customer.getUsername()).roles("BUYER");
    mvc.perform(get("/menu").session(session).with(buyer)).andExpect(status().isOk())
        .andExpect(content().string(containsString("<strong>Khách đặt món</strong>")))
        .andExpect(content().string(containsString("<small>Khách hàng</small>")));
    LocalDate date = schedule.today().plusDays(2);
    String draft = (String) mvc.perform(get("/reservations/new").session(session).with(buyer)
            .param("date", date.toString()).param("tableId", String.valueOf(table)))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("Đặt trước món ăn"))))
        .andReturn().getModelAndView().getModel().get("bookingDraft");
    String receipt = mvc.perform(post("/reservations").session(session).with(buyer).with(csrf()).param("draft", draft)
            .param("tableId", String.valueOf(table)).param("date", date.toString()).param("time", "18:00")
            .param("guests", "2").param("consentAccepted", "true"))
        .andExpect(redirectedUrlPattern("/reservations/confirmation?code=*"))
        .andReturn().getResponse().getRedirectedUrl();
    String code = jdbc.queryForObject("select code from reservations", String.class);
    String orderPath = "/reservations/" + code + "/preorder";
    assertEquals(0, jdbc.queryForObject("select count(*) from dining_orders", Integer.class));
    mvc.perform(get(receipt).session(session).with(buyer))
        .andExpect(content().string(containsString("Bạn có muốn đặt kèm món không?")))
        .andExpect(content().string(containsString("/menu?booking=" + code)));
    mvc.perform(get("/menu").param("booking", code).session(session).with(buyer))
        .andExpect(status().isOk()).andExpect(content().string(containsString(orderPath)));
    mvc.perform(get("/menu").param("booking", code)).andExpect(status().isNotFound());
    var another = accounts.save(UserAccount.customer("Khách khác", null, "0907654000", "qa-other-preorder", "unused"));
    mvc.perform(post(orderPath).with(user(another.getUsername()).roles("BUYER")).with(csrf())
        .param("items[0].dishId", String.valueOf(dish)).param("items[0].quantity", "3")).andExpect(status().isNotFound());
    mvc.perform(post(orderPath).session(session).with(buyer).with(csrf())
            .param("items[0].dishId", String.valueOf(dish)).param("items[0].quantity", "0"))
        .andExpect(redirectedUrl("/menu?booking=" + code)).andExpect(flash().attributeExists("error"));
    mvc.perform(post(orderPath).session(session).with(buyer).with(csrf())
            .param("items[0].dishId", String.valueOf(dish)).param("items[0].quantity", "3")
            .param("items[0].note", "x".repeat(121)))
        .andExpect(redirectedUrl("/menu?booking=" + code)).andExpect(flash().attributeExists("error"));
    assertEquals(0, jdbc.queryForObject("select count(*) from dining_order_items", Integer.class));
    for (int attempt = 0; attempt < 2; attempt++) {
      mvc.perform(post(orderPath).session(session).with(buyer).with(csrf()).param("items[0].dishId", String.valueOf(dish)).param("items[0].quantity", "3")
              .param("items[0].note", "  Không bỏ hành  "))
          .andExpect(redirectedUrl(receipt));
    }
    assertEquals(code, jdbc.queryForObject(
        "select reservations.code from dining_orders join reservations on reservation_id = reservations.id", String.class));
    assertEquals(1, jdbc.queryForObject("select count(*) from dining_order_items", Integer.class));
    assertEquals(3, jdbc.queryForObject("select quantity from dining_order_items", Integer.class));
    assertEquals("Không bỏ hành", jdbc.queryForObject("select note from dining_order_items", String.class));
    mvc.perform(get(receipt).session(session).with(buyer))
        .andExpect(content().string(containsString("Bít tết QA × 3 · 600.000 đ")))
        .andExpect(content().string(containsString("Không bỏ hành")));
    mvc.perform(get("/reservations/new").param("date", date.toString()).param("time", "18:00"))
        .andExpect(content().string(containsString("Đang chờ xác nhận")))
        .andExpect(content().string(not(containsString("Chọn bàn A01"))));
    mvc.perform(get("/reservations/new").param("date", date.toString()).param("time", "12:00"))
        .andExpect(content().string(containsString("Chọn bàn A01")));

    var guestRequest = post("/reservations").with(csrf()).param("tableId", String.valueOf(table))
        .param("date", date.plusDays(1).toString()).param("time", "18:00").param("guests", "2")
        .param("guestName", "Khách giữ chỗ").param("consentAccepted", "true").param("preorderDishIds", String.valueOf(dish));
    mvc.perform(guestRequest).andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("form", "guestPhone"));
    var guest = new MockHttpSession();
    String guestReceipt = mvc.perform(guestRequest.session(guest).param("guestPhone", "0907654321"))
        .andExpect(redirectedUrlPattern("/reservations/confirmation?code=*"))
        .andReturn().getResponse().getRedirectedUrl();
    mvc.perform(get(guestReceipt).session(guest))
        .andExpect(content().string(not(containsString("Bạn có muốn đặt kèm món không?"))));
    assertEquals("PENDING_CONFIRMATION", jdbc.queryForObject(
        "select status from reservations where guest_phone='0907654321'", String.class));
    assertEquals(1, jdbc.queryForObject("select count(*) from dining_order_items", Integer.class));
  }

  @Test
  void protectsLastAdminAndRevokesSessionsAndTokensWhenAnEmployeeIsLocked() throws Exception {
    admin(post("/admin/accounts").param("username", "qa-staff").param("fullName", "Nhân viên Kiểm thử")
        .param("role", "STAFF").param("enabled", "true").param("password", PASSWORD))
        .andExpect(redirectedUrl("/admin/accounts"));
    var staff = accounts.findByUsername("qa-staff").orElseThrow();
    assertNotEquals(PASSWORD, staff.getPassword());
    admin(get("/admin/accounts").param("role", "STAFF").param("q", "Kiểm thử"))
        .andExpect(status().isOk()).andExpect(content().string(containsString("Nhân viên Kiểm thử")))
        .andExpect(content().string(not(containsString(staff.getPassword()))));
    var session = (MockHttpSession) mvc.perform(post("/login").with(csrf()).param("username", "qa-staff").param("password", PASSWORD))
        .andExpect(redirectedUrl("/staff/dashboard")).andReturn().getRequest().getSession(false);
    mvc.perform(get("/staff/dashboard").session(session)).andExpect(status().isOk())
        .andExpect(content().string(containsString("href=\"/menu\">Thực đơn")))
        .andExpect(content().string(containsString("<strong>Nhân viên Kiểm thử</strong>")))
        .andExpect(content().string(containsString("<small>Nhân viên</small>")));
    var tokenBody = mvc.perform(post("/api/auth/login").contentType("application/json")
        .content(json.writeValueAsString(java.util.Map.of("username", "qa-staff", "password", PASSWORD))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    String bearer = "Bearer " + json.readTree(tokenBody).get("accessToken").asText();
    mvc.perform(get("/api/staff/reservations").header("Authorization", bearer)).andExpect(status().isOk());

    long admin = accounts.findByUsername("admin").orElseThrow().getId();
    admin(post("/admin/accounts/" + admin + "/availability").param("enabled", "false")).andExpect(flash().attributeExists("error"));
    admin(post("/admin/accounts/" + admin).param("username", "admin").param("fullName", "Admin")
        .param("role", "STAFF").param("enabled", "true")).andExpect(status().isOk())
        .andExpect(content().string(containsString("Không thể tự khóa hoặc tự hạ quyền")));
    assertEquals("ADMIN", accounts.findByUsername("admin").orElseThrow().getRole());

    admin(post("/admin/accounts/" + staff.getId() + "/availability").param("enabled", "false")).andExpect(flash().attributeExists("notice"));
    mvc.perform(get("/staff/dashboard").session(session)).andExpect(redirectedUrlPattern("**/staff/login"));
    mvc.perform(get("/api/staff/reservations").header("Authorization", bearer)).andExpect(status().isUnauthorized());
    admin(post("/admin/accounts/" + staff.getId() + "/availability").param("enabled", "true")).andExpect(flash().attributeExists("notice"));
    mvc.perform(get("/api/staff/reservations").header("Authorization", bearer)).andExpect(status().isUnauthorized());
    admin(get("/admin/activity")).andExpect(status().isOk()).andExpect(content().string(containsString("Khóa tài khoản")));
  }

  @Test
  void synchronizesMenuAreaAndSettingsWithoutInvalidatingConfirmedBookings() throws Exception {
    var section = id("select id from restaurant_sections where kind='MENU'");
    admin(post("/admin/categories").param("kind", "MENU").param("id", "" + section).param("name", "Món đã ẩn"))
        .andExpect(flash().attributeExists("notice"));
    assertEquals("Món đã ẩn", jdbc.queryForObject("select category_name from dishes where id=?", String.class, dish));
    mvc.perform(get("/menu")).andExpect(status().isOk()).andExpect(content().string(not(containsString("Bít tết QA"))));
    admin(post("/admin/categories").param("kind", "MENU").param("id", "" + section).param("name", "Món đã ẩn").param("active", "true"))
        .andExpect(flash().attributeExists("notice"));
    admin(post("/admin/dishes/" + dish + "/state").param("value", "false").param("stock", "true"))
        .andExpect(flash().attributeExists("notice"));
    mvc.perform(get("/menu")).andExpect(content().string(not(containsString("Bít tết QA"))));
    admin(post("/admin/dishes/" + dish + "/state").param("value", "true").param("stock", "true"));
    mvc.perform(get("/menu")).andExpect(content().string(containsString("Bít tết QA")));
    admin(post("/admin/dishes/" + dish + "/state").param("value", "false"))
        .andExpect(flash().attributeExists("notice"));
    mvc.perform(get("/menu")).andExpect(content().string(not(containsString("Bít tết QA"))));

    long area = id("select id from restaurant_sections where kind='AREA'");
    admin(post("/admin/areas").param("kind", "AREA").param("id", "" + area).param("name", "Sảnh chính"))
        .andExpect(flash().attributeExists("notice"));
    assertThrows(BookingException.class, () -> bookings.create(booking()));
    admin(post("/admin/areas").param("kind", "AREA").param("id", "" + area).param("name", "Sảnh VIP").param("active", "true"))
        .andExpect(flash().attributeExists("notice"));
    var booking = bookings.create(booking());
    admin(post("/admin/reservations/" + booking.getId() + "/status").param("status", "CONFIRMED").param("from", "detail"))
        .andExpect(redirectedUrl("/admin/reservations/" + booking.getId())).andExpect(flash().attributeExists("notice"));
    admin(get("/admin/reservations/" + booking.getId())).andExpect(status().isOk())
        .andExpect(content().string(containsString("/admin/reservations/" + booking.getId() + "/status")));
    admin(post("/admin/areas").param("kind", "AREA").param("id", "" + area).param("name", "Sảnh VIP"))
        .andExpect(flash().attributeExists("error"));
    admin(post("/admin/inventory/" + table).param("code", "A01").param("area","Sảnh VIP").param("capacity","2").param("active","true"))
        .andExpect(status().isOk()).andExpect(content().string(containsString("giảm sức chứa")));

    admin(settings("17:00", "")).andExpect(status().isOk()).andExpect(content().string(containsString("Giờ mới nằm ngoài lịch")));
    admin(settings("23:00", "javascript:alert(1)")).andExpect(status().isOk()).andExpect(content().string(containsString("URL HTTPS")));
    admin(settings("23:00", "")).andExpect(redirectedUrl("/admin/settings"));
    assertEquals(LocalTime.of(23, 0), schedule.getClosingTime());
    assertEquals(45, schedule.getCheckInMinutes());
    assertEquals(20, schedule.getNoShowMinutes());
    mvc.perform(get("/about")).andExpect(status().isOk()).andExpect(content().string(containsString("Nội dung cập nhật từ quản trị")))
        .andExpect(content().string(containsString("Địa chỉ kiểm thử")));
  }

  @Test
  void reportsOnlyCollectedRevenueAndKeepsPaidInvoiceImmutable() throws Exception {
    var first = bookings.create(booking());
    var next = booking();
    next.setDate(next.getDate().plusDays(1));
    var second = bookings.create(next);
    var now = schedule.today().atTime(15, 0);
    long employee = accounts.findByUsername("staff").orElseThrow().getId();
    jdbc.update("insert into staff_shifts(employee_id,opened_at,opening_cash,status) values(?,?,?,'OPEN')", employee, now.minusHours(1), 50000);
    long shift = id("select id from staff_shifts");
    jdbc.update("insert into dining_orders(reservation_id,status,opened_by,opened_at,finalized_at,total_amount) values (?,'PAID','staff',?,?,200000)", first.getId(),now,now);
    jdbc.update("insert into dining_orders(reservation_id,status,opened_by,opened_at,finalized_at,total_amount) values (?,'FINALIZED','staff',?,?,900000)", second.getId(),now,now);
    long order = id("select id from dining_orders where status='PAID'");
    jdbc.update("insert into dining_order_items(order_id,dish_id,dish_name,unit_price,quantity,cancelled) values(?,?,'Tên món lúc bán',200000,1,false)",order,dish);
    long item = id("select id from dining_order_items");
    jdbc.update("insert into dining_payments(order_id,shift_id,amount,payment_method,received_by,paid_at) values(?,?,200000,'CASH','staff',?)",order,shift,now);
    jdbc.update("insert into cash_sales(shift_id,dish_id,dish_name,quantity,unit_price,total_amount,payment_method,created_at) values(?,?,'Bán lẻ',1,50000,50000,'TRANSFER',?)",shift,dish,now);
    jdbc.update("update dishes set price=555000 where id=?",dish);

    admin(get("/admin/revenue")).andExpect(status().isOk()).andExpect(content().string(containsString("250.000 đ")))
        .andExpect(content().string(not(containsString("900.000 đ"))));
    admin(get("/admin/dashboard")).andExpect(status().isOk()).andExpect(content().string(containsString("250.000 đ")));
    admin(get("/admin/invoices").param("method","CASH")).andExpect(content().string(containsString("T3L-HD-" + order)))
        .andExpect(content().string(not(containsString("900.000 đ"))));
    admin(get("/admin/invoices/" + order)).andExpect(status().isOk())
        .andExpect(content().string(containsString("Tên món lúc bán"))).andExpect(content().string(containsString("200.000 đ")));
    admin(post("/admin/orders/" + first.getId() + "/items/" + item).param("quantity", "2")).andExpect(flash().attributeExists("error"));
    admin(post("/admin/dishes/" + dish + "/delete")).andExpect(flash().attributeExists("error"));
    var csv = admin(get("/admin/revenue.csv").param("employee","staff").param("method","CASH"))
        .andExpect(status().isOk()).andExpect(header().string("Content-Disposition",containsString(".csv")))
        .andReturn().getResponse().getContentAsString();
    assertTrue(csv.contains("200000"));
    assertFalse(csv.contains("50000") || csv.contains("900000"));
    admin(get("/admin/shifts")).andExpect(status().isOk()).andExpect(content().string(containsString("Ca #" + shift)));
  }

  private MockHttpServletRequestBuilder settings(String closing, String image) {
    return post("/admin/settings").param("address", "Địa chỉ kiểm thử").param("phone", "0901234567")
        .param("email", "restaurant@example.test").param("aboutText", "Nội dung cập nhật từ quản trị").param("aboutImageUrl", image)
        .param("openingTime", "09:00").param("closingTime", closing).param("durationMinutes", "120").param("slotMinutes", "30")
        .param("changeNoticeMinutes", "60").param("checkInMinutes", "45").param("noShowMinutes", "20");
  }

  private ReservationForm booking() {
    var form = new ReservationForm();
    form.setDate(schedule.today().plusDays(5));
    form.setTime(LocalTime.of(18,0));
    form.setGuests(3);
    form.setGuestName("Khách đặt bàn QA");
    form.setGuestPhone("0901234567");
    form.setConsentAccepted(true);
    return form;
  }

  private ResultActions admin(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.with(user("admin").roles("ADMIN")).with(csrf()));
  }

  private Long id(String sql) { return jdbc.queryForObject(sql,Long.class); }
}
