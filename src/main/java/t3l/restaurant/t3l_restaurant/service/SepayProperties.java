package t3l.restaurant.t3l_restaurant.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class SepayProperties {
  private final boolean enabled;
  private final String apiToken;
  private final String bankName;
  private final String bankAccount;
  private final String accountHolder;
  private final String paymentMode;
  private final long pollIntervalMs;
  private final boolean demoEnabled;
  private final String apiBaseUrl;
  private final int invoicePaymentMinutes;
  private final Environment environment;

  public SepayProperties(@Value("${sepay.enabled:false}") boolean enabled,
      @Value("${sepay.api-token:}") String apiToken,
      @Value("${sepay.bank-name:}") String bankName,
      @Value("${sepay.bank-account:}") String bankAccount,
      @Value("${sepay.account-holder:}") String accountHolder,
      @Value("${sepay.payment-mode:POLLING}") String paymentMode,
      @Value("${sepay.poll-interval-ms:5000}") long pollIntervalMs,
      @Value("${sepay.demo-enabled:false}") boolean demoEnabled,
      @Value("${sepay.api-base-url:https://userapi.sepay.vn/v2}") String apiBaseUrl,
      @Value("${sepay.invoice-payment-minutes:120}") int invoicePaymentMinutes,
      Environment environment) {
    if (pollIntervalMs < 1000 || invoicePaymentMinutes < 1) {
      throw new IllegalArgumentException("Cấu hình SePay không hợp lệ.");
    }
    this.enabled = enabled;
    this.apiToken = apiToken == null ? "" : apiToken.strip();
    this.bankName = clean(bankName);
    this.bankAccount = clean(bankAccount);
    this.accountHolder = clean(accountHolder);
    this.paymentMode = paymentMode == null ? "POLLING" : paymentMode.strip().toUpperCase(Locale.ROOT);
    this.pollIntervalMs = pollIntervalMs;
    this.demoEnabled = demoEnabled;
    this.apiBaseUrl = apiBaseUrl == null ? "https://userapi.sepay.vn/v2" : apiBaseUrl.strip().replaceAll("/+$", "");
    this.invoicePaymentMinutes = invoicePaymentMinutes;
    this.environment = environment;
  }

  public boolean enabled() { return enabled; }
  public String apiToken() { return apiToken; }
  public String bankName() { return bankName; }
  public String bankAccount() { return bankAccount; }
  public String accountHolder() { return accountHolder; }
  public String paymentMode() { return paymentMode; }
  public long pollIntervalMs() { return pollIntervalMs; }
  public String apiBaseUrl() { return apiBaseUrl; }
  public int invoicePaymentMinutes() { return invoicePaymentMinutes; }

  public boolean hasQrAccount() { return !bankName.isBlank() && !bankAccount.isBlank() && !accountHolder.isBlank(); }
  public boolean pollingConfigured() { return enabled && "POLLING".equals(paymentMode) && !apiToken.isBlank(); }
  public boolean paymentReady() { return pollingConfigured() && hasQrAccount(); }

  public boolean demoAllowed() {
    String configuredProfiles = environment.getProperty("spring.profiles.active",
        environment.getProperty("SPRING_PROFILES_ACTIVE", ""));
    return demoEnabled && Stream.concat(Arrays.stream(environment.getActiveProfiles()),
        Arrays.stream(configuredProfiles.split(",")))
        .map(profile -> profile.strip().toLowerCase(Locale.ROOT))
        .anyMatch(profile -> profile.equals("local") || profile.equals("dev") || profile.equals("development")
            || profile.equals("qa") || profile.equals("test"));
  }

  private String clean(String value) { return value == null ? "" : value.strip(); }
}
