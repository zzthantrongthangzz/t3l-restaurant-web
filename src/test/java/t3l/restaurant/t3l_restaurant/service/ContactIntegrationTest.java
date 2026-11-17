package t3l.restaurant.t3l_restaurant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
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
import t3l.restaurant.t3l_restaurant.config.RestaurantInfo;
import t3l.restaurant.t3l_restaurant.dto.ContactForm;
import t3l.restaurant.t3l_restaurant.entity.ContactMessage;
import t3l.restaurant.t3l_restaurant.repository.ContactMessageRepository;

@SpringBootTest
@AutoConfigureMockMvc
class ContactIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ContactService service;
  @Autowired ContactMessageRepository messages;
  @Autowired RestaurantInfo info;
  private final Set<Long> ownIds = new HashSet<>();

  @AfterEach void cleanup() { messages.deleteAllByIdInBatch(ownIds); }

  private ContactForm form() {
    var form = new ContactForm(); form.setName("Contact QA"); form.setEmail("qa@example.com");
    form.setPhone("0901234567"); form.setSubject("CONTACT_QA_" + UUID.randomUUID());
    form.setMessage("Nội dung liên hệ kiểm thử tích hợp."); return form;
  }
  private ContactMessage save(ContactForm form) {
    var saved = service.submit(form); ownIds.add(saved.getId()); return saved;
  }
  private MockHttpSession role(String role) {
    var session = new MockHttpSession(); var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new UsernamePasswordAuthenticationToken("contact-qa-admin", "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context); return session;
  }
  private String csrf(MockHttpSession session) throws Exception {
    var html = mvc.perform(get("/contact").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    var match = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html); assertTrue(match.find()); return match.group(1);
  }

  @Test void publicPagesUseSharedShellAndConfiguredContactInformation() throws Exception {
    assertTrue(info.getAddress().contains("Lê Lợi"));
    assertTrue(info.getMapsUrl().contains("L%C3%AA"));
    for (String path : List.of("/", "/menu", "/reservations/new", "/reservations/lookup", "/about", "/contact")) {
      mvc.perform(get(path)).andExpect(status().isOk())
          .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/about\"")))
          .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/contact\"")))
          .andExpect(content().string(org.hamcrest.Matchers.containsString(info.getPhone())))
          .andExpect(content().string(org.hamcrest.Matchers.containsString(info.getEmail())));
    }
    mvc.perform(get("/reservations/confirmation")).andExpect(status().isNotFound())
        .andExpect(content().string(org.hamcrest.Matchers.containsString(info.getPhone())));
    mvc.perform(get("/contact")).andExpect(content().string(org.hamcrest.Matchers.containsString("output=embed")));
  }

  @Test void validSubmissionIsPersistedBeforeSuccessAndRedirectContainsNoPrivateData() throws Exception {
    var form = form(); var session = new MockHttpSession();
    mvc.perform(post("/contact").session(session).param("_csrf", csrf(session))
        .param("name", "  " + form.getName() + "  ").param("email", form.getEmail()).param("phone", form.getPhone())
        .param("subject", form.getSubject()).param("message", form.getMessage()))
        .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/contact#contact-form"))
        .andExpect(flash().attributeExists("contactNotice"));
    var saved = messages.findAll().stream().filter(item -> item.getSubject().equals(form.getSubject())).findFirst().orElseThrow();
    ownIds.add(saved.getId()); assertEquals(form.getName(), saved.getName());
    assertEquals(form.getMessage(), saved.getMessage()); assertEquals("NEW", saved.getStatus());
    assertNotNull(saved.getCreatedAt());
  }

  @Test void invalidInputsAndMissingCsrfCannotCreateMessages() throws Exception {
    var session = new MockHttpSession(); long count = messages.count();
    mvc.perform(post("/contact").session(session).param("_csrf", csrf(session)).param("name", " ")
        .param("email", "invalid-email").param("phone", "123").param("subject", " ").param("message", "short"))
        .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("contactForm", "name", "email", "phone", "subject", "message"))
        .andExpect(flash().attributeCount(0));
    mvc.perform(post("/contact").param("name", "QA")).andExpect(status().isForbidden());
    assertEquals(count, messages.count());
    var invalid = form(); invalid.setEmail("wrong"); assertThrows(IllegalArgumentException.class, () -> service.submit(invalid));
  }

  @Test void onlyAdminCanViewAndManageContactsAndMessagesAreEscaped() throws Exception {
    var form = form(); form.setMessage("<script>alert('contact')</script>\nNội dung riêng tư."); var saved = save(form);
    mvc.perform(get("/admin/contacts/" + saved.getId())).andExpect(status().is3xxRedirection());
    var staff = role("STAFF");
    mvc.perform(get("/admin/contacts").session(staff)).andExpect(status().isForbidden());
    mvc.perform(post("/admin/contacts/" + saved.getId() + "/status").session(staff).param("_csrf", csrf(staff))
        .param("version", "0").param("status", "RESOLVED")).andExpect(status().isForbidden());
    var admin = role("ADMIN");
    mvc.perform(get("/admin/contacts").session(admin)).andExpect(status().isOk()).andExpect(view().name("admin/contacts"));
    mvc.perform(get("/admin/contacts/" + saved.getId()).session(admin)).andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>alert"))));
    mvc.perform(post("/admin/contacts/" + saved.getId() + "/status").session(admin).param("_csrf", csrf(admin))
        .param("version", "0").param("status", "IN_PROGRESS").param("adminNote", "Đã tiếp nhận xử lý"))
        .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("notice"));
    var updated = service.find(saved.getId()); assertEquals("IN_PROGRESS", updated.getStatus());
    assertEquals("contact-qa-admin", updated.getHandledBy()); assertEquals("Đã tiếp nhận xử lý", updated.getAdminNote());
  }

  @Test void statusValidationAndVersionCheckPreservePreviousUpdates() throws Exception {
    var saved = save(form());
    service.update(saved.getId(), saved.getVersion(), "RESOLVED", "Đã phản hồi", "qa-admin");
    assertThrows(IllegalArgumentException.class, () -> service.update(saved.getId(), saved.getVersion(), "NEW", "stale", "other"));
    var fresh = service.find(saved.getId());
    assertThrows(IllegalArgumentException.class, () -> service.update(fresh.getId(), fresh.getVersion(), "INVALID", "", "qa-admin"));
    assertThrows(IllegalArgumentException.class, () -> service.update(fresh.getId(), fresh.getVersion(), "NEW", "x".repeat(1001), "qa-admin"));
    assertEquals("RESOLVED", service.find(saved.getId()).getStatus()); assertEquals("Đã phản hồi", service.find(saved.getId()).getAdminNote());
    mvc.perform(get("/admin/contacts").session(role("ADMIN")).param("status", "RESOLVED"))
        .andExpect(status().isOk()).andExpect(model().attribute("filterStatus", "RESOLVED"));
    mvc.perform(get("/admin/contacts/9223372036854775807").session(role("ADMIN"))).andExpect(status().isNotFound());
    mvc.perform(get("/admin/contacts").session(role("ADMIN")).param("status", "INVALID")).andExpect(status().isBadRequest());
  }
}
