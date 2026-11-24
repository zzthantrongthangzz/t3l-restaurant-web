package t3l.restaurant.t3l_restaurant.service;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.dto.CustomerProfileForm;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.CustomerProfileRepository;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;
import t3l.restaurant.t3l_restaurant.security.GoogleOAuthFlow;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:customerAccounts;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.sql.init.mode=never", "app.google.client-id=local-test-client", "app.google.client-secret=local-test-secret",
    "app.sms.local-enabled=true", "server.address=127.0.0.1", "app.sms.local-phone=0900000000", "app.sms.api-key=",
    "app.jwt.secret=Y3VzdG9tZXItYWNjb3VudHMtdGVzdC1rZXktdGhpcy1pcy1mb3ItdGVzdHMtb25seQ=="
})
@AutoConfigureMockMvc
class CustomerAccountIntegrationTest {
  private static final String PASSWORD = "Only for local test 123!";
  @Autowired MockMvc mvc;
  @Autowired UserAccountRepository accounts;
  @Autowired ReservationRepository bookings;
  @Autowired CustomerAccountService customerAccounts;
  @Autowired CustomerProfileRepository profiles;
  @Autowired GoogleCustomerService google;
  @Autowired GoogleOAuthFlow oauth;
  @Autowired DatabaseUserDetailsService users;
  @Autowired PasswordEncoder passwords;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper objectMapper;
  @SpyBean EsmsOtpSender esms;

  @Test
  void rendersCustomerPagesAndProtectsProfileAndAddressActions() throws Exception {
    var customer = account();
    var other = account();
    var viewer = user(customer.getUsername()).roles("BUYER");
    var intruder = user(other.getUsername()).roles("BUYER");
    mvc.perform(get("/account")).andExpect(status().is3xxRedirection());
    mvc.perform(get("/account").with(user("employee").roles("STAFF"))).andExpect(status().isForbidden());
    for (String path : List.of("/account", "/account/reservations", "/account/orders", "/account/vouchers",
        "/account/addresses", "/account/addresses/new", "/account/invoices", "/account/invoices?tab=company")) {
      mvc.perform(get(path).with(viewer)).andExpect(status().isOk())
          .andExpect(content().string(containsString("Menu tài khoản của bạn")));
    }
    mvc.perform(post("/account").with(viewer)).andExpect(status().isForbidden());
    mvc.perform(post("/account").with(viewer).with(csrf()).param("givenName", "Linh")
        .param("familyName", "Nguyễn").param("email", customer.getEmail()).param("phone", "")
        .param("dietaryPreferences", "Ăn chay").param("bookingNotifications", "true"))
        .andExpect(redirectedUrl("/account"));
    assertEquals("Nguyễn Linh", accounts.findById(customer.getId()).orElseThrow().getFullName());
    assertTrue(profiles.findById(customer.getId()).orElseThrow().isBookingNotifications());
    assertFalse(profiles.findById(customer.getId()).orElseThrow().isMarketingOptIn());
    mvc.perform(post("/account").with(viewer).with(csrf()).param("givenName", "Linh")
        .param("email", other.getEmail())).andExpect(model().attributeHasFieldErrors("profileForm", "email"));

    for (String address : List.of("12 Test Street", "22 Test Street")) {
      mvc.perform(post("/account/addresses/new").with(viewer).with(csrf()).param("recipientName", "Linh")
          .param("phone", "0901234567").param("streetAddress", address))
          .andExpect(redirectedUrl("/account/addresses"));
    }
    var addresses = customerAccounts.addresses(customer.getId());
    Long first = addresses.getFirst().getId();
    Long second = addresses.getLast().getId();
    mvc.perform(get("/account/addresses/{id}", second).with(viewer)).andExpect(status().isOk());
    mvc.perform(get("/account/addresses/{id}", second).with(intruder)).andExpect(status().isNotFound());
    mvc.perform(post("/account/addresses/{id}/delete", second).with(intruder).with(csrf()))
        .andExpect(status().isNotFound());
    mvc.perform(post("/account/addresses/{id}/default", second).with(viewer).with(csrf()))
        .andExpect(redirectedUrl("/account/addresses"));
    assertEquals(second, customerAccounts.addresses(customer.getId()).getFirst().getId());
    mvc.perform(post("/account/addresses/{id}/delete", second).with(viewer).with(csrf()))
        .andExpect(redirectedUrl("/account/addresses"));
    assertEquals(first, customerAccounts.addresses(customer.getId()).getFirst().getId());
    assertTrue(customerAccounts.addresses(customer.getId()).getFirst().isDefaultAddress());

    mvc.perform(post("/account/invoices/company").with(viewer).with(csrf()).param("companyName", "T3L test buyer")
        .param("taxCode", "1234567890").param("companyAddress", "12 Test Street")
        .param("invoiceEmail", customer.getEmail())).andExpect(redirectedUrl("/account/invoices?tab=company"));
    mvc.perform(get("/account/invoices").with(viewer)).andExpect(content().string(containsString("Chưa có đơn hàng đủ điều kiện")));
  }

  @Test
  void onlyOwnerCanReadAndUpdateLinkedBookingAndVerifiedGuestCanClaimLegacyBooking() throws Exception {
    var owner = account();
    var other = account();
    var signedIn = user(owner.getUsername()).roles("BUYER");
    var intruder = user(other.getUsername()).roles("BUYER");
    jdbc.update("insert into restaurant_tables(code, table_type, capacity, active) values (?, ?, ?, ?)",
        "CUSTOMER-QA-TABLE", "Bàn nhỏ", 4, true);
    var bookingSession = new MockHttpSession();
    String draft = bookingDraft(bookingSession, owner);
    mvc.perform(post("/reservations").session(bookingSession).with(signedIn).with(csrf()).param("draft", draft)
        .param("guestName", "Khách T3L")
        .param("guestPhone", "0901234567").param("date", "2098-09-05").param("time", "18:00")
        .param("guests", "2").param("consentAccepted", "true"))
        .andExpect(status().is3xxRedirection());
    var booking = bookings.findByCustomerIdOrderByReservationDateAscReservationTimeAscIdAsc(owner.getId()).getFirst();
    mvc.perform(post("/reservations").session(bookingSession).with(signedIn).with(csrf()).param("draft", draft)
        .param("date", "2098-09-05").param("time", "18:00").param("guests", "2").param("consentAccepted", "true"))
        .andExpect(redirectedUrl("/reservations/new")).andExpect(flash().attributeExists("bookingNotice"));
    assertEquals(1, bookings.findByCustomerIdOrderByReservationDateAscReservationTimeAscIdAsc(owner.getId()).size());
    mvc.perform(get("/account/reservations").with(signedIn)).andExpect(content().string(containsString(booking.getCode())));
    mvc.perform(get("/account/reservations").with(intruder)).andExpect(content().string(not(containsString(booking.getCode()))));
    mvc.perform(get("/reservations/lookup").with(signedIn).param("code", booking.getCode()))
        .andExpect(model().attributeExists("reservation"));
    mvc.perform(get("/reservations/lookup").with(intruder).param("code", booking.getCode()))
        .andExpect(model().attributeDoesNotExist("reservation"));
    mvc.perform(get("/reservations/confirmation").with(intruder).param("code", booking.getCode()))
        .andExpect(status().isNotFound());
    mvc.perform(post("/reservations/lookup/{code}/cancel", booking.getCode()).with(intruder).with(csrf())
        .param("reason", "Không thể đến")).andExpect(redirectedUrl("/reservations/lookup"));
    mvc.perform(post("/reservations/lookup/{code}/cancel", booking.getCode()).with(signedIn).with(csrf())
        .param("reason", "Không thể đến")).andExpect(status().is3xxRedirection())
        .andExpect(flash().attributeExists("lookupNotice"));

    var legacy = legacyBooking();
    var verified = new MockHttpSession();
    mvc.perform(post("/reservations/lookup").with(signedIn).with(csrf()).session(verified)
        .param("code", legacy.getCode()).param("phone", legacy.getGuestPhone()))
        .andExpect(status().is3xxRedirection());
    mvc.perform(post("/reservations/lookup/{code}/claim", legacy.getCode()).with(signedIn).with(csrf())
        .session(verified)).andExpect(redirectedUrl("/account/reservations"));
    assertEquals(owner.getId(), bookings.findByCode(legacy.getCode()).orElseThrow().getCustomerId());
  }

  @Test
  void googleSubjectSignsInWhileMatchingEmailRequiresExplicitLink() throws Exception {
    var customer = account();
    var identity = googleUser("subject-" + customer.getUsername(), customer.getEmail(), true);
    mvc.perform(get("/login")).andExpect(content().string(containsString("Đăng nhập bằng Google")));
    mvc.perform(get("/oauth2/authorization/google")).andExpect(header().string("Location",
        org.hamcrest.Matchers.startsWith("https://accounts.google.com/")));
    assertEquals("google_unverified", assertThrows(OAuth2AuthenticationException.class,
        () -> google.signIn(googleUser("unverified", customer.getEmail(), false))).getError().getErrorCode());
    assertEquals("email_in_use", assertThrows(OAuth2AuthenticationException.class,
        () -> google.signIn(identity)).getError().getErrorCode());
    assertNull(customerAccounts.profile(customer).getGoogleSubject());
    customer.verifyPhone("0901234567");
    accounts.saveAndFlush(customer);

    var context = SecurityContextHolder.createEmptyContext();
    var principal = users.loadUserByUsername(customer.getUsername());
    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    var session = new MockHttpSession();
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

    mvc.perform(post("/account/google/link").session(session)).andExpect(status().isForbidden());
    String authorization = mvc.perform(post("/account/google/link").session(session).with(csrf()))
        .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
    var uri = UriComponentsBuilder.fromUriString(authorization).build();
    assertEquals("accounts.google.com", uri.getHost());
    assertNotNull(uri.getQueryParams().getFirst("code_challenge"));
    var request = new MockHttpServletRequest();
    request.setSession(session);
    request.setParameter("state", URLDecoder.decode(uri.getQueryParams().getFirst("state"), StandardCharsets.UTF_8));
    var response = new MockHttpServletResponse();
    try {
      oauth.onSuccess(request, response, new OAuth2AuthenticationToken(identity, identity.getAuthorities(), "google"));
      assertEquals("/account?google=linked", response.getRedirectedUrl());
    } finally {
      SecurityContextHolder.clearContext();
    }
    assertEquals(customer.getUsername(), google.signIn(identity));
    assertEquals(identity.getSubject(), profiles.findById(customer.getId()).orElseThrow().getGoogleSubject());
    assertEquals(customer.getUsername(), google.signIn(googleUser(identity.getSubject(), "updated." + customer.getEmail(), true)));
    assertEquals("updated." + customer.getEmail(), profiles.findById(customer.getId()).orElseThrow().getGoogleEmail());
    assertTrue(passwords.matches(PASSWORD, accounts.findById(customer.getId()).orElseThrow().getPassword()));
  }

  @Test
  void googleSignInReturnsToThePageAndQueryWhereLoginStarted() throws Exception {
    String target = "/reservations/new?date=2098-09-05";
    mvc.perform(get(target)).andExpect(model().attribute("loginReturnTo", target))
        .andExpect(content().string(containsString("href=\"/login?returnTo=")));
    mvc.perform(get("/login").param("returnTo", target)).andExpect(model().attribute("googleReturnTo", target))
        .andExpect(content().string(containsString("href=\"/oauth2/authorization/google?returnTo=")));
    var session = new MockHttpSession();
    String state = startGoogleSignIn(session, target);
    assertEquals(target, completeGoogleSignIn(session, state, "/menu"));
  }

  @Test
  void googleSignInFallsBackToHomeWithoutAnAllowedPage() throws Exception {
    for (String target : List.of("", "https://example.invalid", "///menu", "/login")) {
      var session = new MockHttpSession();
      String state = startGoogleSignIn(session, target);
      assertEquals("/", completeGoogleSignIn(session, state, null));
    }
  }

  @Test
  void cancellingGoogleSignInRetainsThePageForARetry() throws Exception {
    String target = "/reservations/new?date=2098-09-05";
    var session = new MockHttpSession();
    String state = startGoogleSignIn(session, target);
    String location = mvc.perform(get("/login/oauth2/code/google").session(session)
        .param("error", "access_denied").param("state", state))
        .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
    var parameters = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
    assertEquals("failed", parameters.getFirst("google"));
    assertEquals(target, URLDecoder.decode(parameters.getFirst("returnTo"), StandardCharsets.UTF_8));
  }

  private String startGoogleSignIn(MockHttpSession session, String target) throws Exception {
    String location = mvc.perform(get("/oauth2/authorization/google").param("returnTo", target).session(session))
        .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
    var authorization = UriComponentsBuilder.fromUriString(location).build();
    assertFalse(authorization.getQueryParams().containsKey("returnTo"));
    return URLDecoder.decode(authorization.getQueryParams().getFirst("state"), StandardCharsets.UTF_8);
  }

  private String completeGoogleSignIn(MockHttpSession session, String state, String callbackTarget) throws Exception {
    return completeGoogleSignIn(session, state, callbackTarget,
        googleUser("return-test-user", "return-test-user@example.test", true));
  }

  private String completeGoogleSignIn(MockHttpSession session, String state, String callbackTarget,
      DefaultOidcUser identity) throws Exception {
    var request = new MockHttpServletRequest();
    request.setSession(session);
    request.setParameter("state", state);
    if (callbackTarget != null) request.setParameter("returnTo", callbackTarget);
    var response = new MockHttpServletResponse();
    assertNotNull(oauth.requests().removeAuthorizationRequest(request, response));
    try {
      oauth.onSuccess(request, response, new OAuth2AuthenticationToken(identity, identity.getAuthorities(), "google"));
      return response.getRedirectedUrl();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void firstGoogleSignInCreatesOneCustomerWithoutPhone() throws Exception {
    var identity = googleUser("new-google-user", "new-google-user@example.test", true);
    String username = google.signIn(identity);
    assertEquals(username, google.signIn(identity));
    var account = accounts.findByUsername(username).orElseThrow();
    assertEquals("BUYER", account.getRole());
    assertEquals("Google Guest", account.getFullName());
    assertNull(account.getPhone());
    assertEquals("https://lh3.googleusercontent.com/mock-avatar", customerAccounts.profile(account).getAvatarUrl());
    mvc.perform(get("/account").with(user(username).roles("BUYER"))).andExpect(status().isOk())
        .andExpect(content().string(containsString("Đã liên kết")));
  }

  @Test
  void passwordsRemainAvailableOnlyForEmployees() throws Exception {
    var buyer = account();
    var employee = accounts.save(new UserAccount("employee-" + UUID.randomUUID(), passwords.encode(PASSWORD), "STAFF"));
    mvc.perform(get("/login")).andExpect(status().isOk())
        .andExpect(content().string(not(containsString("name=\"password\""))));
    mvc.perform(get("/register")).andExpect(status().isOk())
        .andExpect(content().string(not(containsString("name=\"password\""))));
    mvc.perform(get("/staff/reservations")).andExpect(redirectedUrlPattern("**/staff/login"));
    mvc.perform(get("/staff/login")).andExpect(content().string(containsString("name=\"password\"")));
    mvc.perform(post("/login").with(csrf()).param("username", buyer.getUsername()).param("password", PASSWORD))
        .andExpect(redirectedUrl("/staff/login?error"));
    mvc.perform(post("/api/auth/login").contentType("application/json")
        .content(objectMapper.writeValueAsString(Map.of("username", buyer.getUsername(), "password", PASSWORD))))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/login").with(csrf()).param("username", employee.getUsername()).param("password", PASSWORD))
        .andExpect(redirectedUrl("/staff/dashboard"));
    mvc.perform(post("/api/auth/login").contentType("application/json")
        .content(objectMapper.writeValueAsString(Map.of("username", employee.getUsername(), "password", PASSWORD))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isString());
  }

  @Test
  void registersAndSignsInWithSmsWithoutAnExistingSessionLogin() throws Exception {
    String phone = "0971234567";
    var code = simulateSms();
    var session = new MockHttpSession();
    mvc.perform(post("/register").session(session).with(csrf())
        .param("fullName", "Nguyễn An").param("phone", phone))
        .andExpect(status().isOk()).andExpect(model().attribute("registrationPending", true));
    assertNotNull(code.get());
    mvc.perform(post("/register/verify").session(session).with(csrf()).param("code", code.get()))
        .andExpect(redirectedUrl("/"));
    assertEquals("Nguyễn An", accounts.findByVerifiedPhone(phone).orElseThrow().getFullName());
    mvc.perform(get("/account").session(session)).andExpect(status().isOk());
  }

  @Test
  void registrationResumesTheGuestTableDraft() throws Exception {
    jdbc.update("insert into restaurant_tables(code, table_type, capacity, active) values (?, ?, ?, ?)",
        "REGISTER-QA-TABLE", "Bàn nhỏ", 4, true);
    Long tableId = jdbc.queryForObject("select id from restaurant_tables where code='REGISTER-QA-TABLE'", Long.class);
    var session = new MockHttpSession();
    String draft = (String) mvc.perform(get("/reservations/new").session(session)
        .param("date", "2098-12-17").param("time", "18:00").param("guests", "3").param("tableId", tableId.toString()))
        .andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("bookingDraft");
    String target = "/reservations/new?draft=" + draft;
    mvc.perform(get("/register").session(session).param("returnTo", target))
        .andExpect(model().attribute("registrationReturnTo", target));
    var sentCode = simulateSms();
    mvc.perform(post("/register").session(session).with(csrf()).param("returnTo", target)
        .param("fullName", "Khách đăng ký").param("phone", "0966543210"))
        .andExpect(model().attribute("registrationPending", true));
    mvc.perform(post("/register/verify").session(session).with(csrf()).param("code", sentCode.get()))
        .andExpect(redirectedUrl(target));
    var resumed = (ReservationForm) mvc.perform(get(target).session(session))
        .andExpect(content().string(containsString("Yêu cầu đặt REGISTER-QA-TABLE")))
        .andReturn().getModelAndView().getModel().get("form");
    assertEquals(tableId, resumed.getTableId());
    assertEquals(LocalTime.of(18, 0), resumed.getTime());
    assertEquals("Khách đăng ký", resumed.getGuestName());
  }

  @Test
  void localSmsKeepsBookingDraftAndRequiresProfileBeforeCreatingBooking() throws Exception {
    jdbc.update("insert into restaurant_tables(code, table_type, capacity, active) values (?, ?, ?, ?)",
        "SMS-QA-TABLE", "Bàn nhỏ", 4, true);
    var session = new MockHttpSession();
    String date = "2098-11-19";
    long before = bookings.count();
    String next = mvc.perform(post("/reservations").session(session).with(csrf()).param("date", date)
        .param("guestName", "Nguyễn Linh").param("guestPhone", "0900000000")
        .param("time", "18:00").param("guests", "3").param("preferredArea", "Bàn nhỏ")
        .param("note", "Ghi chú cần giữ").param("consentAccepted", "true").param("loginOnly", "true"))
        .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
    var login = UriComponentsBuilder.fromUriString(next).build();
    assertEquals("/login", login.getPath());
    String target = URLDecoder.decode(login.getQueryParams().getFirst("returnTo"), StandardCharsets.UTF_8);
    String draft = UriComponentsBuilder.fromUriString(target).build().getQueryParams().getFirst("draft");
    assertEquals(before, bookings.count());
    mvc.perform(get(java.net.URI.create(next)).session(session)).andExpect(status().isOk())
        .andExpect(content().string(containsString("Tiếp tục với số điện thoại")));

    String code = (String) mvc.perform(post("/login/sms/send").session(session).with(csrf())
        .param("phone", "0900000000").param("returnTo", target))
        .andExpect(content().string(containsString("Chưa gửi SMS.")))
        .andExpect(model().attribute("requestAccepted", false)).andReturn().getModelAndView().getModel().get("localCode");
    assertNotNull(code);
    assertTrue(code.matches("[0-9]{6}"));
    mvc.perform(post("/login/sms/send").session(session).with(csrf()).param("phone", "0900000000"))
        .andExpect(content().string(containsString("quá thường xuyên")));
    mvc.perform(post("/login/sms/verify").with(csrf()).param("code", code))
        .andExpect(content().string(containsString("đã hết hạn")));
    mvc.perform(post("/login/sms/verify").session(session).with(csrf()).param("code", code))
        .andExpect(redirectedUrl(target));
    assertTrue(accounts.findByVerifiedPhone("0900000000").isEmpty());
    var account = accounts.findByUsername("local-sms:0900000000").orElseThrow();
    mvc.perform(get(target).session(session)).andExpect(status().isOk())
        .andExpect(content().string(containsString("HOÀN THIỆN HỒ SƠ CỦA BẠN")))
        .andExpect(content().string(containsString("0900000000")))
        .andExpect(model().attribute("profileForm", org.hamcrest.Matchers.hasProperty("givenName",
            org.hamcrest.Matchers.equalTo("Linh"))))
        .andExpect(model().attribute("form", org.hamcrest.Matchers.hasProperty("note",
            org.hamcrest.Matchers.equalTo("Ghi chú cần giữ"))));
    mvc.perform(post("/reservations/profile").session(session).with(csrf()).param("draft", draft)
        .param("givenName", "Linh").param("familyName", "Nguyễn").param("phone", "0900000000")
        .param("bookingNotifications", "true")).andExpect(redirectedUrl(target));
    mvc.perform(get(target).session(session))
        .andExpect(content().string(not(containsString("HOÀN THIỆN HỒ SƠ CỦA BẠN"))))
        .andExpect(content().string(containsString("Nguyễn Linh")));
    mvc.perform(post("/reservations").session(session).with(csrf()).param("draft", draft)
        .param("date", date).param("time", "18:00").param("guests", "3").param("preferredArea", "Bàn nhỏ")
        .param("guestName", "Tampered").param("guestPhone", "0988888888").param("guestEmail", "fake@example.test")
        .param("note", "Ghi chú cần giữ").param("consentAccepted", "true"))
        .andExpect(redirectedUrlPattern("/reservations/confirmation?code=*"));
    var saved = bookings.findByCustomerIdOrderByReservationDateAscReservationTimeAscIdAsc(account.getId()).getFirst();
    assertEquals("Nguyễn Linh", saved.getGuestName());
    assertEquals("0900000000", saved.getGuestPhone());
    assertNull(saved.getGuestEmail());
    assertFalse(profiles.findById(account.getId()).orElseThrow().isMarketingOptIn());
    mvc.perform(get("/account/reservations").session(session))
        .andExpect(content().string(containsString(saved.getCode())));
    mvc.perform(post("/login/sms/verify").session(session).with(csrf()).param("code", code))
        .andExpect(content().string(containsString("đã hết hạn")));
  }

  @Test
  void verifiedSmsWithLegacyPasswordPreservesTheExistingAccount() throws Exception {
    String phone = "0911234567";
    var legacy = accounts.save(UserAccount.customer("Khách cũ", null, "+84 911 234 567",
        "legacy:" + UUID.randomUUID(), passwords.encode(PASSWORD)));
    var oldBooking = legacyBooking();
    oldBooking.assignCustomer(legacy);
    bookings.saveAndFlush(oldBooking);
    AtomicReference<String> code = simulateSms();
    var session = new MockHttpSession();
    mvc.perform(post("/login/sms/send").session(session).with(csrf()).param("phone", phone).param("returnTo", "/menu"))
        .andExpect(model().attribute("requestAccepted", true)).andExpect(model().attributeDoesNotExist("localCode"));
    mvc.perform(post("/login/sms/verify").session(session).with(csrf()).param("code", code.get()))
        .andExpect(redirectedUrl("/login/recover"));
    assertTrue(accounts.findByVerifiedPhone(phone).isEmpty());
    mvc.perform(post("/login/recover").session(session).with(csrf())
        .param("username", legacy.getUsername()).param("password", "wrong"))
        .andExpect(model().attributeExists("recoveryError"));
    mvc.perform(post("/login/recover").session(session).with(csrf())
        .param("username", legacy.getUsername()).param("password", PASSWORD))
        .andExpect(redirectedUrl("/menu"));
    assertEquals(legacy.getId(), accounts.findByVerifiedPhone(phone).orElseThrow().getId());
    assertEquals(legacy.getId(), bookings.findByCode(oldBooking.getCode()).orElseThrow().getCustomerId());
  }

  @Test
  void fiveWrongSmsCodesInvalidateTheChallenge() throws Exception {
    var sent = simulateSms();
    var session = new MockHttpSession();
    mvc.perform(post("/login/sms/send").session(session).with(csrf()).param("phone", "0921234567"))
        .andExpect(model().attribute("requestAccepted", true));
    String wrong = "000000".equals(sent.get()) ? "111111" : "000000";
    for (int i = 0; i < 5; i++) {
      mvc.perform(post("/login/sms/verify").session(session).with(csrf()).param("code", wrong))
          .andExpect(model().attributeExists("smsError"));
    }
    mvc.perform(post("/login/sms/verify").session(session).with(csrf()).param("code", sent.get()))
        .andExpect(content().string(containsString("đã hết hạn")));
    assertTrue(accounts.findByVerifiedPhone("0921234567").isEmpty());
  }

  @Test
  void liveOtpCreatesOneVerifiedAccountWithoutANameOrEmail() throws Exception {
    String phone = "0931234567";
    var code = simulateSms();
    var session = new MockHttpSession();
    mvc.perform(post("/login/sms/send").session(session).with(csrf()).param("phone", "+84 931 234 567"))
        .andExpect(model().attribute("requestAccepted", true));
    mvc.perform(post("/login/sms/verify").session(session).with(csrf()).param("code", code.get()))
        .andExpect(redirectedUrl("/"));
    var account = accounts.findByVerifiedPhone(phone).orElseThrow();
    assertEquals("BUYER", account.getRole());
    assertNull(account.getEmail());
    assertNull(account.getFullName());
    mvc.perform(get("/account").session(session)).andExpect(content().string(containsString("Đã xác thực qua SMS")));
  }

  @Test
  void legacyGoogleRecoveryRequiresTheOldPasswordAndKeepsTheSameAccount() throws Exception {
    var legacy = account();
    var identity = googleUser("legacy-" + legacy.getUsername(), legacy.getEmail(), true);
    var session = new MockHttpSession();
    String state = startGoogleSignIn(session, "/menu");
    var request = new MockHttpServletRequest();
    request.setSession(session);
    request.setParameter("state", state);
    var response = new MockHttpServletResponse();
    assertNotNull(oauth.requests().removeAuthorizationRequest(request, response));
    try {
      oauth.onSuccess(request, response, new OAuth2AuthenticationToken(identity, identity.getAuthorities(), "google"));
      assertEquals("/login/recover", response.getRedirectedUrl());
    } finally {
      SecurityContextHolder.clearContext();
    }
    assertTrue(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) == null);
    mvc.perform(post("/login/recover").session(session).with(csrf())
        .param("username", legacy.getUsername()).param("password", "wrong"))
        .andExpect(model().attributeExists("recoveryError"));
    mvc.perform(post("/login/recover").session(session).with(csrf())
        .param("username", legacy.getUsername()).param("password", PASSWORD))
        .andExpect(redirectedUrl("/menu"));
    assertEquals(legacy.getId(), profiles.findAccountIdByGoogleSubject(identity.getSubject()).orElseThrow());
  }

  @Test
  void googleBookingPrefillsContactAndKeepsSelectionThroughTheProfilePopup() throws Exception {
    jdbc.update("insert into restaurant_tables(code, table_type, capacity, active) values (?, ?, ?, ?)",
        "GOOGLE-QA-TABLE", "Bàn nhỏ", 4, true);
    var session = new MockHttpSession();
    String date = "2098-12-09";
    Long tableId = jdbc.queryForObject("select id from restaurant_tables where code='GOOGLE-QA-TABLE'", Long.class);
    String guestDraft = (String) mvc.perform(get("/reservations/new").session(session)
        .param("date", date).param("time", "18:30").param("guests", "3").param("tableId", tableId.toString()))
        .andExpect(view().name("web/table-plan")).andExpect(content().string(containsString("Yêu cầu đặt GOOGLE-QA-TABLE")))
        .andReturn().getModelAndView().getModel().get("bookingDraft");
    String start = mvc.perform(post("/reservations").session(session).with(csrf()).param("date", date)
        .param("draft", guestDraft).param("tableId", tableId.toString())
        .param("time", "18:30").param("guests", "3").param("note", "Ghi chú của lịch Google")
        .param("consentAccepted", "true").param("loginOnly", "true")).andReturn().getResponse().getRedirectedUrl();
    assertNotNull(start);
    String target = URLDecoder.decode(UriComponentsBuilder.fromUriString(start).build()
        .getQueryParams().getFirst("returnTo"), StandardCharsets.UTF_8);
    String draft = UriComponentsBuilder.fromUriString(target).build().getQueryParams().getFirst("draft");
    assertEquals(guestDraft, draft);
    var identity = googleUser("google-booking-qa", "google-booking-qa@example.test", true);
    String state = startGoogleSignIn(session, target);
    assertEquals(target, completeGoogleSignIn(session, state, null, identity));

    var model = mvc.perform(get(target).session(session)).andExpect(status().isOk())
        .andExpect(content().string(containsString("HOÀN THIỆN HỒ SƠ CỦA BẠN")))
        .andReturn().getModelAndView().getModel();
    var profile = (CustomerProfileForm) model.get("profileForm");
    assertEquals("Guest", profile.getGivenName());
    assertEquals("Google", profile.getFamilyName());
    assertEquals(identity.getEmail(), profile.getEmail());
    assertNull(profile.getPhone());
    mvc.perform(post("/reservations/profile").session(session).with(csrf()).param("draft", draft)
        .param("givenName", profile.getGivenName()).param("familyName", profile.getFamilyName())
        .param("email", profile.getEmail())).andExpect(model().attributeHasFieldErrors("profileForm", "phone"));
    mvc.perform(post("/reservations/profile").session(session).with(csrf()).param("draft", draft)
        .param("givenName", profile.getGivenName()).param("familyName", profile.getFamilyName())
        .param("email", profile.getEmail()).param("phone", "0901234568")).andExpect(redirectedUrl(target));

    var resumed = (ReservationForm) mvc.perform(get(target).session(session)).andExpect(status().isOk())
        .andExpect(model().attribute("bookingProfileMissing", false)).andReturn().getModelAndView().getModel().get("form");
    assertEquals(LocalDate.parse(date), resumed.getDate());
    assertEquals(LocalTime.of(18, 30), resumed.getTime());
    assertEquals(3, resumed.getGuests());
    assertEquals("Ghi chú của lịch Google", resumed.getNote());
    assertEquals(tableId, resumed.getTableId());
    assertTrue(resumed.isConsentAccepted());
    assertEquals(identity.getEmail(), resumed.getGuestEmail());
    mvc.perform(post("/reservations").session(session).with(csrf()).param("draft", draft)
        .param("tableId", tableId.toString())
        .param("date", date).param("time", "18:30").param("guests", "3").param("consentAccepted", "true"))
        .andExpect(redirectedUrlPattern("/reservations/confirmation?code=*"));
    Long owner = profiles.findAccountIdByGoogleSubject(identity.getSubject()).orElseThrow();
    var saved = bookings.findByCustomerIdOrderByReservationDateAscReservationTimeAscIdAsc(owner).getFirst();
    assertEquals(identity.getEmail(), saved.getGuestEmail());
    assertEquals(tableId, bookings.findOneById(saved.getId()).orElseThrow().getTable().getId());
    mvc.perform(get("/account/reservations").session(session)).andExpect(content().string(containsString(saved.getCode())));
  }

  @Test
  void googleSignInRepairsMissingContactWithoutOverwritingCustomerEdits() {
    var identity = googleUser("missing-google-contact", "missing-google-contact@example.test", true);
    String username = google.signIn(identity);
    var account = accounts.findByUsername(username).orElseThrow();
    account.updateCustomerContact(null, null, null);
    accounts.saveAndFlush(account);
    assertEquals(username, google.signIn(identity));
    account = accounts.findByUsername(username).orElseThrow();
    assertEquals("Google Guest", account.getFullName());
    assertEquals(identity.getEmail(), account.getEmail());

    account.updateCustomerContact("Tên khách đã sửa", "updated-contact@example.test", "0901234569");
    accounts.saveAndFlush(account);
    assertEquals(username, google.signIn(identity));
    account = accounts.findByUsername(username).orElseThrow();
    assertEquals("Tên khách đã sửa", account.getFullName());
    assertEquals("updated-contact@example.test", account.getEmail());
    assertEquals("0901234569", account.getPhone());
  }

  private AtomicReference<String> simulateSms() {
    var sent = new AtomicReference<String>();
    doReturn(true).when(esms).live();
    doAnswer(call -> { sent.set(call.getArgument(1)); return true; }).when(esms).send(anyString(), anyString(), anyString());
    return sent;
  }

  private UserAccount account() {
    String username = "qa_" + UUID.randomUUID().toString().substring(0, 16);
    return accounts.save(UserAccount.customer("Khách T3L", username + "@example.test", "0901234567",
        username, passwords.encode(PASSWORD)));
  }

  private String bookingDraft(MockHttpSession session, UserAccount owner) throws Exception {
    String draft = (String) mvc.perform(get("/reservations/new").session(session)
        .with(user(owner.getUsername()).roles("BUYER"))).andExpect(status().isOk())
        .andReturn().getModelAndView().getModel().get("bookingDraft");
    assertNotNull(draft);
    return draft;
  }

  private Reservation legacyBooking() {
    String code = "T3L-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(java.util.Locale.ROOT);
    var reservation = new Reservation(code, "Khách T3L", "0901234567", null, null,
        LocalDate.of(2098, 9, 5), LocalTime.of(18, 0), 2, null, true);
    return bookings.save(reservation);
  }

  private DefaultOidcUser googleUser(String subject, String email, boolean verified) {
    Instant now = Instant.now();
    return new DefaultOidcUser(List.of(), new OidcIdToken("mock-google-id-token", now, now.plusSeconds(60),
        Map.of("sub", subject, "email", email, "email_verified", verified, "name", "Google Guest",
            "given_name", "Guest", "family_name", "Google",
            "picture", "https://lh3.googleusercontent.com/mock-avatar")));
  }
}
