package t3l.restaurant.t3l_restaurant.service;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.URI;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import t3l.restaurant.t3l_restaurant.entity.CustomerProfile;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.CustomerProfileRepository;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;
import t3l.restaurant.t3l_restaurant.service.PasswordResetService.Step;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:passwordReset;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.sql.init.mode=never", "spring.mail.username=t3l-qa@example.test", "spring.mail.password=test-only",
    "app.reservation-mail.worker-enabled=false", "app.tracking.enabled=false"
})
@AutoConfigureMockMvc
class PasswordResetIntegrationTest {
  private static final String PATH = "/login/forgot-password";
  private static final String OLD_PASSWORD = "Existing password 123!";
  private static final String NEW_PASSWORD = "Replacement password 123!";

  @Autowired MockMvc mvc;
  @Autowired UserAccountRepository accounts;
  @Autowired CustomerProfileRepository profiles;
  @Autowired PasswordEncoder passwords;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transactions;
  @MockBean JavaMailSender mail;
  @MockBean(name = "applicationTaskExecutor") AsyncTaskExecutor executor;

  @BeforeEach
  void runDeliveryTasks() {
    jdbc.update("delete from sms_otp_limits");
    doAnswer(call -> {
      call.getArgument(0, Runnable.class).run();
      return null;
    }).when(executor).execute(any(Runnable.class));
  }

  @Test
  void resetsPasswordOnceAndRevokesAnExistingWebSession() throws Exception {
    String phone = "0930000001";
    var account = customer("phone:" + phone, phone, "verified@example.test");
    var oldSession = new MockHttpSession();
    login(oldSession, phone, OLD_PASSWORD).andExpect(redirectedUrl("/"));
    mvc.perform(get("/account").session(oldSession)).andExpect(status().isOk());

    var reset = new MockHttpSession();
    mvc.perform(get("/login")).andExpect(status().isOk())
        .andExpect(content().string(containsString(PATH)))
        .andExpect(content().string(not(containsString("/contact#contact-form"))));
    mvc.perform(post(PATH + "/request").session(reset).param("phone", phone)).andExpect(status().isForbidden());
    mvc.perform(get(PATH).session(reset)).andExpect(status().isOk()).andExpect(model().attribute("resetStep", Step.PHONE));
    mvc.perform(post(PATH + "/request").session(reset).with(csrf()).param("phone", "+84930000001")
        .param("returnTo", "/reservations/new")).andExpect(redirectedUrl(PATH));
    var messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail).send(messages.capture());
    assertArrayEquals(new String[]{"verified@example.test"}, messages.getValue().getTo());
    mvc.perform(get(PATH).session(reset)).andExpect(status().isOk()).andExpect(model().attribute("resetStep", Step.CODE))
        .andExpect(content().string(containsString("***@***")))
        .andExpect(content().string(not(containsString("verified@example.test"))));
    String code = code(messages.getValue());
    String pendingSessionId = reset.getId();
    verifyCode(reset, code).andExpect(redirectedUrl(PATH)).andExpect(flash().attributeCount(0));
    assertNotEquals(pendingSessionId, reset.getId());
    mvc.perform(get(PATH).session(reset)).andExpect(status().isOk()).andExpect(model().attribute("resetStep", Step.PASSWORD))
        .andExpect(content().string(containsString("v***@e***.test")))
        .andExpect(content().string(not(containsString("verified@example.test"))));
    verifyCode(reset, code).andExpect(flash().attributeExists("resetError"));
    change(reset, NEW_PASSWORD, "incorrect confirmation").andExpect(flash().attributeExists("resetError"));
    var redirect = change(reset, NEW_PASSWORD, NEW_PASSWORD).andExpect(redirectedUrl("/login?returnTo=%2Freservations%2Fnew"))
        .andExpect(flash().attribute("passwordResetSuccess", true)).andReturn();
    mvc.perform(get(URI.create(redirect.getResponse().getRedirectedUrl())).session(reset))
        .andExpect(content().string(containsString("Đã đổi mật khẩu thành công")));

    var saved = accounts.findById(account.getId()).orElseThrow();
    assertTrue(passwords.matches(NEW_PASSWORD, saved.getPassword()));
    assertEquals(1, saved.getAuthVersion());
    mvc.perform(get("/account").session(oldSession)).andExpect(status().is3xxRedirection());
    login(new MockHttpSession(), phone, OLD_PASSWORD).andExpect(model().attributeExists("passwordError"));
    login(new MockHttpSession(), phone, NEW_PASSWORD).andExpect(redirectedUrl("/"));
    change(reset, NEW_PASSWORD, NEW_PASSWORD).andExpect(flash().attributeExists("resetError"));
  }

  @Test
  void unavailableAccountsReceiveTheSameScreenWithoutSendingMail() throws Exception {
    String unverified = "0930000002";
    String googleOnly = "0930000003";
    customer("phone:" + unverified, unverified, null);
    customer("google:only", googleOnly, "googleonly@example.test");
    for (String phone : new String[]{unverified, googleOnly, "0930000004"}) {
      var session = new MockHttpSession();
      requestCode(session, phone).andExpect(redirectedUrl(PATH)).andExpect(flash().attributeCount(0));
      mvc.perform(get(PATH).session(session)).andExpect(status().isOk()).andExpect(model().attribute("resetStep", Step.CODE))
          .andExpect(content().string(containsString("***@***")))
          .andExpect(content().string(not(containsString(phone))));
      verifyCode(session, "000000").andExpect(flash().attribute("resetError", "Mã xác minh chưa đúng. Bạn còn 4 lần thử."));
    }
    verify(executor, times(3)).execute(any(Runnable.class));
    verifyNoInteractions(mail);
  }

  @Test
  void rateLimitsResendsAndInvalidatesExhaustedAndReplacedCodes() throws Exception {
    String phone = "0930000005";
    customer("phone:" + phone, phone, "resend@example.test");
    var session = new MockHttpSession();
    requestCode(session, phone).andExpect(redirectedUrl(PATH));
    var messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail).send(messages.capture());
    String old = code(messages.getValue());
    resend(session).andExpect(flash().attributeExists("resetError"));
    requestCode(new MockHttpSession(), phone).andExpect(flash().attributeExists("resetError"));
    String wrong = (old.startsWith("0") ? "1" : "0") + old.substring(1);
    for (int i = 0; i < 5; i++) verifyCode(session, wrong).andExpect(flash().attributeExists("resetError"));
    verifyCode(session, old).andExpect(flash().attribute("resetError", "Bạn đã nhập sai quá nhiều lần. Vui lòng yêu cầu mã mới."));

    allowResend(phone);
    resend(session).andExpect(flash().attributeCount(0));
    var resent = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail, times(2)).send(resent.capture());
    String replacement = code(resent.getValue());
    assertNotEquals(old, replacement);
    verifyCode(session, old).andExpect(flash().attributeExists("resetError"));
    verifyCode(new MockHttpSession(), replacement).andExpect(flash().attributeExists("resetError"));
    verifyCode(session, replacement).andExpect(flash().attributeCount(0));
  }

  @Test
  void aSecondVerifiedFlowCannotOverwriteANewerPassword() throws Exception {
    String phone = "0930000006";
    customer("phone:" + phone, phone, "parallel@example.test");
    var first = new MockHttpSession();
    var second = new MockHttpSession();
    requestCode(first, phone).andExpect(flash().attributeCount(0));
    var messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail).send(messages.capture());
    verifyCode(first, code(messages.getValue())).andExpect(flash().attributeCount(0));
    allowResend(phone);
    requestCode(second, phone).andExpect(flash().attributeCount(0));
    var twoMessages = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail, times(2)).send(twoMessages.capture());
    verifyCode(second, code(twoMessages.getValue())).andExpect(flash().attributeCount(0));
    change(first, NEW_PASSWORD, NEW_PASSWORD).andExpect(flash().attribute("passwordResetSuccess", true));
    change(second, OLD_PASSWORD, OLD_PASSWORD).andExpect(flash().attributeExists("resetError"));
    login(new MockHttpSession(), phone, NEW_PASSWORD).andExpect(redirectedUrl("/"));
  }

  private UserAccount customer(String username, String phone, String verifiedEmail) {
    return transactions.execute(status -> {
      var account = accounts.saveAndFlush(UserAccount.customer("Reset QA", username.replace(':', '.') + "@contact.test",
          phone, username, passwords.encode(OLD_PASSWORD)));
      if (verifiedEmail != null) {
        var profile = new CustomerProfile(account);
        profile.updateGoogle("google-subject-" + phone, "Reset QA", verifiedEmail, null);
        profiles.saveAndFlush(profile);
      }
      return account;
    });
  }

  private ResultActions requestCode(MockHttpSession session, String phone) throws Exception {
    return mvc.perform(post(PATH + "/request").session(session).with(csrf()).param("phone", phone));
  }

  private ResultActions resend(MockHttpSession session) throws Exception {
    return mvc.perform(post(PATH + "/request").session(session).with(csrf()));
  }

  private ResultActions verifyCode(MockHttpSession session, String code) throws Exception {
    return mvc.perform(post(PATH + "/verify").session(session).with(csrf()).param("code", code));
  }

  private ResultActions change(MockHttpSession session, String change, String confirm) throws Exception {
    return mvc.perform(post(PATH + "/reset").session(session).with(csrf()).param("password", change).param("confirmPassword", confirm));
  }

  private ResultActions login(MockHttpSession session, String phone, String password) throws Exception {
    return mvc.perform(post("/login/password").session(session).with(csrf()).param("phone", phone).param("password", password));
  }

  private void allowResend(String phone) {
    jdbc.update("update sms_otp_limits set last_hit_at = timestamp '2000-01-01 00:00:00' where rate_key = ?",
        "password-reset:phone:" + phone);
  }

  private static String code(SimpleMailMessage mail) {
    return Pattern.compile("[0-9]{6}").matcher(mail.getText()).results().findFirst().orElseThrow().group();
  }
}
