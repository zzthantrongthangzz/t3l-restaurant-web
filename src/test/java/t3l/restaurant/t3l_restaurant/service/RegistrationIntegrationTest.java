package t3l.restaurant.t3l_restaurant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import t3l.restaurant.t3l_restaurant.dto.RegistrationForm;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;
import t3l.restaurant.t3l_restaurant.service.RegistrationService.DuplicateAccountException;

@SpringBootTest
@AutoConfigureMockMvc
class RegistrationIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired UserAccountRepository accounts;
  @Autowired RegistrationService registration;
  @Autowired PasswordEncoder encoder;
  @Autowired ObjectMapper json;
  private final Set<String> ownUsernames = ConcurrentHashMap.newKeySet();

  @AfterEach void cleanup() {
    var ids = ownUsernames.stream().map(accounts::findByUsername).flatMap(Optional::stream).map(a -> a.getId()).toList();
    accounts.deleteAllByIdInBatch(ids);
  }

  private RegistrationForm form() {
    var form = new RegistrationForm();
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    form.setFullName("  Khách hàng đăng ký  ");
    form.setEmail("  signupqa_" + suffix + "@example.com  ");
    form.setPhone("  +84 901 234 567  ");
    form.setUsername("  signupqa_" + suffix + "  ");
    form.setPassword("  Signup QA 123!  "); form.setConfirmPassword(form.getPassword());
    ownUsernames.add(form.getUsername());
    return form;
  }

  private MockHttpServletRequestBuilder postForm(RegistrationForm form) {
    return post("/register").with(csrf()).param("fullName", form.getFullName()).param("email", form.getEmail())
        .param("phone", form.getPhone()).param("username", form.getUsername())
        .param("password", form.getPassword()).param("confirmPassword", form.getConfirmPassword());
  }

  @Test void anonymousFormUsesSharedShellCsrfAndLinks() throws Exception {
    mvc.perform(get("/register")).andExpect(status().isOk()).andExpect(view().name("auth/register"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"home-footer\"")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/login\"")));
    mvc.perform(get("/login")).andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/register\"")));
  }

  @Test void registrationStoresBcryptAndProfileAndOnlyCustomerCanSignInWithSessionAndJwt() throws Exception {
    var form = form();
    var result = mvc.perform(postForm(form).param("role", "ADMIN").param("enabled", "false").param("id", "1"))
        .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"))
        .andExpect(flash().attributeExists("registrationNotice")).andReturn();
    var account = accounts.findByUsername(form.getUsername()).orElseThrow();
    assertEquals("Khách hàng đăng ký", account.getFullName());
    assertEquals(form.getEmail(), account.getEmail()); assertEquals(form.getPhone(), account.getPhone());
    assertEquals("BUYER", account.getRole()); assertTrue(account.isEnabled());
    assertTrue(account.getPassword().startsWith("$2")); assertNotEquals(form.getPassword(), account.getPassword());
    assertTrue(encoder.matches(form.getPassword(), account.getPassword()));
    assertFalse(encoder.matches(form.getPassword().strip(), account.getPassword()));
    mvc.perform(get("/login").flashAttrs(result.getFlashMap())).andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Đăng ký thành công")));
    var login = mvc.perform(post("/login").with(csrf()).param("username", form.getUsername()).param("password", form.getPassword()))
        .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/")).andReturn();
    var session = (MockHttpSession) login.getRequest().getSession(false);
    var context = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    assertEquals(List.of("ROLE_BUYER"), context.getAuthentication().getAuthorities().stream().map(Object::toString).toList());
    mvc.perform(get("/admin/contacts").session(session)).andExpect(status().isForbidden());
    mvc.perform(get("/staff/reservations").session(session)).andExpect(status().isForbidden());
    var tokenResult = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("username", form.getUsername(), "password", form.getPassword()))))
        .andExpect(status().isOk()).andReturn();
    String token = json.readTree(tokenResult.getResponse().getContentAsString()).get("accessToken").asText();
    var claims = json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    assertEquals("ROLE_BUYER", claims.get("roles").get(0).asText());
    mvc.perform(get("/api/staff/reservations").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    mvc.perform(post("/logout").session(session).with(csrf())).andExpect(status().is3xxRedirection());
  }

  @Test void invalidFieldsAndMissingCsrfNeverPersistAccounts() throws Exception {
    var form = form(); long before = accounts.count();
    mvc.perform(post("/register").param("username", form.getUsername())).andExpect(status().isForbidden());
    form.setFullName(" "); form.setPhone("123"); form.setEmail("invalid-email"); form.setUsername("bad username");
    form.setPassword("short"); form.setConfirmPassword("");
    mvc.perform(postForm(form)).andExpect(status().isOk())
        .andExpect(model().attributeHasFieldErrors("registrationForm", "fullName", "phone", "email", "username", "password", "confirmPassword"));
    assertEquals(before, accounts.count());
  }

  @Test void mismatchPreservesEscapedProfileWithoutEchoingPasswords() throws Exception {
    var form = form(); form.setFullName("<script>alert(1)</script>"); form.setConfirmPassword("DifferentSecret_123!");
    String html = mvc.perform(postForm(form)).andExpect(status().isOk())
        .andExpect(model().attributeHasFieldErrors("registrationForm", "passwordsMatching"))
        .andReturn().getResponse().getContentAsString();
    assertTrue(html.contains("&lt;script&gt;")); assertFalse(html.contains("<script>alert(1)</script>"));
    assertFalse(html.contains(form.getPassword())); assertFalse(html.contains(form.getConfirmPassword()));
    assertFalse(accounts.existsByUsername(form.getUsername()));
  }

  @Test void usernameAndEmailDuplicatesAreRejectedCaseInsensitively() throws Exception {
    var first = form(); registration.register(first);
    var second = form(); second.setUsername(first.getUsername().toUpperCase(Locale.ROOT));
    mvc.perform(postForm(second)).andExpect(status().isOk())
        .andExpect(model().attributeHasFieldErrors("registrationForm", "username"));
    second = form(); second.setEmail(first.getEmail().toUpperCase(Locale.ROOT));
    mvc.perform(postForm(second)).andExpect(status().isOk())
        .andExpect(model().attributeHasFieldErrors("registrationForm", "email"));
    assertFalse(accounts.existsByUsername(second.getUsername()));
  }

  @Test void unicodePasswordsRespectBcryptByteLimit() {
    var valid = form(); valid.setPassword("ế".repeat(24)); valid.setConfirmPassword(valid.getPassword());
    var account = registration.register(valid); assertTrue(encoder.matches(valid.getPassword(), account.getPassword()));
    var invalid = form(); invalid.setPassword("ế".repeat(25)); invalid.setConfirmPassword(invalid.getPassword());
    assertThrows(ConstraintViolationException.class, () -> registration.register(invalid));
    assertFalse(accounts.existsByUsername(invalid.getUsername()));
  }

  @Test void simultaneousDuplicateEmailsCreateOnlyOneAccount() throws Exception { concurrentRegistration(false); }
  @Test void simultaneousDuplicateUsernamesCreateOnlyOneAccount() throws Exception { concurrentRegistration(true); }

  private void concurrentRegistration(boolean sameUsername) throws Exception {
    List<RegistrationForm> forms = new ArrayList<>();
    for (int i = 0; i < 4; i++) forms.add(form());
    var first = forms.get(0);
    for (var form : forms) {
      if (sameUsername) form.setUsername(first.getUsername()); else form.setEmail(first.getEmail());
    }
    var executor = Executors.newFixedThreadPool(4); var start = new CountDownLatch(1);
    var successes = new AtomicInteger(); var duplicates = new AtomicInteger();
    try {
      var futures = forms.stream().map(form -> executor.submit(() -> {
        start.await();
        try { registration.register(form); successes.incrementAndGet(); }
        catch (DuplicateAccountException | DataIntegrityViolationException exception) { duplicates.incrementAndGet(); }
        return null;
      })).toList();
      start.countDown();
      for (var future : futures) future.get(30, TimeUnit.SECONDS);
      assertEquals(1, successes.get()); assertEquals(3, duplicates.get());
    } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
  }
}
