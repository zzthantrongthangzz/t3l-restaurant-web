package t3l.restaurant.t3l_restaurant.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import t3l.restaurant.t3l_restaurant.entity.PaymentIntent;
import t3l.restaurant.t3l_restaurant.repository.PaymentIntentRepository;

@Service
@RequiredArgsConstructor
public class SepayPollingService {
  private static final Logger log = LoggerFactory.getLogger(SepayPollingService.class);
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final int BATCH_SIZE = 12;

  private final RestClient.Builder restClientBuilder;
  private final PaymentIntentRepository intents;
  private final PaymentVerificationService verification;
  private final SepayProperties properties;
  private final ReservationSchedule schedule;
  private final AtomicLong retryAtMillis = new AtomicLong();
  private final AtomicLong lastRequestMillis = new AtomicLong();
  private final AtomicLong lastPolledIntentId = new AtomicLong();
  private volatile int consecutiveFailures;

  @Scheduled(fixedDelayString = "${sepay.poll-interval-ms:5000}")
  public void poll() {
    if (!properties.pollingConfigured()) return;
    long nowMillis = System.currentTimeMillis();
    if (nowMillis < retryAtMillis.get()) return;
    LocalDateTime now = LocalDateTime.now(schedule.getClock());
    List<PaymentIntent> waiting = intents.findPollableAfter(now.minusHours(24), lastPolledIntentId.get(),
        PageRequest.of(0, BATCH_SIZE));
    if (waiting.isEmpty() && lastPolledIntentId.get() > 0) {
      lastPolledIntentId.set(0);
      waiting = intents.findPollableAfter(now.minusHours(24), 0L, PageRequest.of(0, BATCH_SIZE));
    }
    if (waiting.isEmpty()) return;

    for (PaymentIntent intent : waiting) {
      if (System.currentTimeMillis() < retryAtMillis.get()) return;
      try {
        waitForRateSlot();
        JsonNode response = requestTransactions(intent, now);
        consecutiveFailures = 0;
        retryAtMillis.set(0);
        handleResponse(intent, response);
      } catch (HttpStatusCodeException exception) {
        handleHttpFailure(exception);
        if (exception.getStatusCode().value() == 429 || exception.getStatusCode().is5xxServerError()) return;
      } catch (RuntimeException exception) {
        backoff(30);
        log.warn("SePay polling request failed; retrying after backoff ({})", exception.getClass().getSimpleName());
        return;
      }
    }
    lastPolledIntentId.set(waiting.size() < BATCH_SIZE ? 0L : waiting.getLast().getId());
  }

  private JsonNode requestTransactions(PaymentIntent intent, LocalDateTime now) {
    LocalDateTime from = intent.getCreatedAt().minusMinutes(2);
    LocalDateTime to = now.isAfter(intent.getExpiresAt().plusHours(24)) ? intent.getExpiresAt().plusHours(24) : now;
    URI uri = UriComponentsBuilder.fromUriString(properties.apiBaseUrl() + "/transactions")
        .queryParam("q", intent.getPaymentCode())
        .queryParam("transaction_date_from", from.format(DATE))
        .queryParam("transaction_date_to", to.format(DATE))
        .queryParam("transaction_date_sort", "asc")
        .queryParam("per_page", 100)
        .queryParam("timestamp_format", "iso8601")
        .build().encode().toUri();
    return restClientBuilder.build().get().uri(uri)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken())
        .header(HttpHeaders.ACCEPT, "application/json")
        .retrieve().body(JsonNode.class);
  }

  private void handleResponse(PaymentIntent intent, JsonNode response) {
    if (response == null || !response.path("data").isArray()) return;
    List<PaymentVerificationService.Incoming> transactions = new ArrayList<>();
    response.path("data").forEach(node -> {
      var parsed = parse(node);
      if (parsed != null) transactions.add(parsed);
    });
    if (transactions.isEmpty()) return;
    // Prefer an exact, timely deposit when a payer first sent an incorrect amount and then corrected it.
    var exact = transactions.stream().filter(tx -> looksValid(intent, tx)).findFirst().orElse(null);
    var selected = exact == null ? transactions.getFirst() : exact;
    try {
      verification.process(intent.getId(), selected, false);
    } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
      log.info("SePay transaction was already claimed; duplicate event ignored.");
    } catch (BookingException exception) {
      log.warn("SePay payment was not applied and requires reconciliation.");
    }
  }

  private PaymentVerificationService.Incoming parse(JsonNode node) {
    String id = text(node, "id");
    LocalDateTime at = date(node.path("transaction_date").asText(null));
    if (id == null || at == null) return null;
    return new PaymentVerificationService.Incoming(id, text(node, "reference_number"), text(node, "account_number"),
        text(node, "transfer_type"), decimal(node.path("amount_in")), decimal(node.path("amount_out")),
        String.join(" ", firstNonBlank(text(node, "transaction_content"), ""), firstNonBlank(text(node, "code"), "")), at);
  }

  private boolean looksValid(PaymentIntent intent, PaymentVerificationService.Incoming tx) {
    return tx.amountIn() != null && tx.amountIn().compareTo(intent.getAmount()) == 0
        && tx.amountOut() != null && tx.amountOut().signum() == 0 && "in".equalsIgnoreCase(tx.direction())
        && tx.transactionAt() != null && !tx.transactionAt().isBefore(intent.getCreatedAt().minusMinutes(2))
        && !tx.transactionAt().isAfter(intent.getExpiresAt())
        && digits(tx.account()).equals(digits(properties.bankAccount()))
        && normalize(tx.content()).contains(normalize(intent.getPaymentCode()));
  }

  private void handleHttpFailure(HttpStatusCodeException exception) {
    int status = exception.getStatusCode().value();
    if (status == 429) {
      String retryAfter = exception.getResponseHeaders() == null ? null
          : exception.getResponseHeaders().getFirst("Retry-After");
      long seconds = parseSeconds(retryAfter, 5);
      backoff(seconds);
      log.warn("SePay returned HTTP 429; pausing polling for {} seconds.", seconds);
      return;
    }
    if (status == 401 || status == 403) backoff(60);
    else if (status >= 500) backoff(Math.min(300, 5L << Math.min(++consecutiveFailures, 5)));
    log.warn("SePay returned HTTP {}; retrying after backoff.", status);
  }

  private void waitForRateSlot() {
    long previous = lastRequestMillis.get();
    long wait = 400 - (System.currentTimeMillis() - previous);
    if (previous > 0 && wait > 0) {
      try { Thread.sleep(wait); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    lastRequestMillis.set(System.currentTimeMillis());
  }

  private void backoff(long seconds) { retryAtMillis.set(System.currentTimeMillis() + Math.max(1, seconds) * 1000); }
  private long parseSeconds(String value, long fallback) {
    if (value == null || value.isBlank()) return fallback;
    try { return Math.max(1, Long.parseLong(value.trim())); }
    catch (NumberFormatException ignored) {
      try { return Math.max(1, Duration.parse(value.trim()).toSeconds()); }
      catch (RuntimeException invalid) { return fallback; }
    }
  }

  private String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private BigDecimal decimal(JsonNode node) {
    if (node == null || node.isNull()) return BigDecimal.ZERO;
    try { return node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText()); }
    catch (NumberFormatException invalid) { return BigDecimal.ZERO; }
  }

  private LocalDateTime date(String value) {
    if (value == null || value.isBlank()) return null;
    try { return LocalDateTime.parse(value, DATE); }
    catch (RuntimeException ignored) {
      try { return java.time.OffsetDateTime.parse(value).toLocalDateTime(); }
      catch (RuntimeException invalid) { return null; }
    }
  }

  private String firstNonBlank(String first, String second) {
    return first == null || first.isBlank() ? second : first;
  }

  private String normalize(String value) { return value == null ? "" : value.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", ""); }
  private String digits(String value) { return value == null ? "" : value.replaceAll("\\D", ""); }
}
