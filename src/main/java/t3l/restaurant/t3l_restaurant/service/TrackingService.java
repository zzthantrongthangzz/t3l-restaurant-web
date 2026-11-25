package t3l.restaurant.t3l_restaurant.service;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseCookie;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import t3l.restaurant.t3l_restaurant.dto.TrackingInput;
import t3l.restaurant.t3l_restaurant.entity.Dish;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.repository.DishRepository;
import t3l.restaurant.t3l_restaurant.repository.TrackingRepository;
import t3l.restaurant.t3l_restaurant.repository.TrackingRepository.Event;
import t3l.restaurant.t3l_restaurant.repository.TrackingRepository.Visit;

@Service
public class TrackingService {
  public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  public static final int IDLE_MINUTES = 30;
  public static final int RETENTION_DAYS = 90;
  private static final String COOKIE = "t3l_analytics";
  private static final String FLOW = TrackingService.class.getName() + ".booking";
  private static final Set<String> PAGES = Set.of("home", "menu", "about", "contact", "faq",
      "booking", "lookup", "confirmation", "login", "account", "history", "orders", "vouchers",
      "addresses", "invoices", "preorder");
  private static final Set<String> DISH_EVENTS = Set.of("DISH_VIEW", "DISH_CLICK", "CART_ADD", "CART_REMOVE", "CART_QUANTITY");
  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(TrackingService.class);

  private record Actor(String browser, Long user) {
    String kind() { return user == null ? "GUEST" : "BUYER"; }
  }
  private record Flow(String id, String browser, LocalDateTime touched) {}
  private final TrackingRepository repository;
  private final DishRepository dishes;
  private final CustomerAccountService accounts;
  private final TransactionTemplate transactions;
  private final byte[] secret;
  private final boolean enabled;
  private final Object[] locks = new Object[64];

  public TrackingService(TrackingRepository repository, DishRepository dishes, CustomerAccountService accounts,
      PlatformTransactionManager manager, @Value("${app.jwt.secret}") String secret,
      @Value("${app.tracking.enabled:true}") boolean enabled) {
    this.repository = repository;
    this.dishes = dishes;
    this.accounts = accounts;
    this.transactions = new TransactionTemplate(manager);
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.enabled = enabled;
    Arrays.setAll(locks, index -> new Object());
  }

  public boolean eligible(Authentication auth) {
    if (!enabled) return false;
    if (auth == null) return true;
    if (auth.getAuthorities().stream().anyMatch(role -> Set.of("ROLE_ADMIN", "ROLE_STAFF").contains(role.getAuthority()))) {
      return false;
    }
    return auth.getAuthorities().stream().anyMatch(role -> "ROLE_ANONYMOUS".equals(role.getAuthority())
        || "ROLE_BUYER".equals(role.getAuthority()) && !auth.getName().startsWith("local-sms:"));
  }

  public String preference(HttpServletRequest request, Authentication auth) {
    if (!eligible(auth)) return "disabled";
    if (browser(request) != null) return "allowed";
    return "off".equals(cookieValue(request)) ? "denied" : "unset";
  }

  public ResponseCookie consent(HttpServletRequest request, boolean allowed) {
    String browser = browser(request);
    if (!allowed && browser != null) safely(() -> locked(browser, () -> {
      repository.revoke(browser, now());
      return null;
    }));
    String value = "off";
    if (allowed) {
      String id = browser == null ? UUID.randomUUID().toString() : browser;
      String payload = id + "." + Instant.now().plus(Duration.ofDays(RETENTION_DAYS)).getEpochSecond();
      value = payload + "." + signature(payload);
    }
    String path = request.getContextPath();
    return ResponseCookie.from(COOKIE, value).path(path.isBlank() ? "/" : path)
        .httpOnly(true).secure(request.isSecure()).sameSite("Lax")
        .maxAge(Duration.ofDays(RETENTION_DAYS)).build();
  }

  public boolean batch(HttpServletRequest request, Authentication auth, TrackingInput.Batch batch) {
    Actor actor = actor(request, auth);
    if (actor == null) return true;
    LocalDateTime now = now();
    var session = request.getSession(false);
    Object marker = session == null ? null : session.getAttribute(FLOW);
    Flow activeFlow = marker instanceof Flow flow && flow.browser().equals(actor.browser())
        && flow.touched().plusMinutes(IDLE_MINUTES).isAfter(now) ? flow : null;
    boolean bookingActivity = activeFlow != null && batch.events().stream()
        .anyMatch(event -> "ACTIVE".equals(event.type()) && "booking".equals(event.page()) && event.seconds() != null);
    boolean accepted = locked(actor.browser(), () -> {
      Visit visit = visit(actor, now);
      boolean newWindow = visit.window() == null || !visit.window().plusMinutes(1).isAfter(now);
      int count = newWindow ? 0 : visit.requests();
      if (count >= 12) return false;
      repository.update(visit.id(), actor.user(), now, newWindow ? now : visit.window(), count + 1);
      List<Dish> catalog = batch.events().stream().anyMatch(event -> requiresCatalog(event.type()))
          ? dishes.findByAvailableTrueOrderByCategoryNameAscNameAsc() : List.of();
      Map<Long,Dish> byId = new HashMap<>();
      catalog.forEach(dish -> byId.put(dish.getId(), dish));
      var keywords = catalog.stream().flatMap(dish -> java.util.stream.Stream.of(dish.getName(), dish.getCategoryName()))
          .filter(Objects::nonNull).map(TrackingService::normalize).distinct().toList();
      for (var input : batch.events()) {
        Event event = validate(input, byId, keywords, catalog);
        if (event != null && bookingActivity && "ACTIVE".equals(event.type()) && "booking".equals(event.page())) {
          event = new Event(event.key(), event.type(), event.page(), null, null, null,
              event.seconds(), activeFlow.id(), null, null);
        }
        if (event != null) repository.save(visit.id(), actor.kind(), now, event);
      }
      return true;
    });
    if (accepted && bookingActivity) {
      synchronized (session) {
        if (session.getAttribute(FLOW) == activeFlow) session.setAttribute(FLOW, new Flow(activeFlow.id(), actor.browser(), now));
      }
    }
    return accepted;
  }

  private boolean requiresCatalog(String type) {
    return DISH_EVENTS.contains(type) || Set.of("SEARCH", "CATEGORY").contains(type);
  }

  private Event validate(TrackingInput.Event input, Map<Long,Dish> dishesById, List<String> keywords, List<Dish> catalog) {
    if (!PAGES.contains(input.page())) return null;
    String type = input.type();
    Long dishId = null;
    String term = null;
    Integer quantity = null;
    Integer seconds = null;
    if (DISH_EVENTS.contains(type)) {
      if (input.dishId() == null || !dishesById.containsKey(input.dishId())) return null;
      dishId = input.dishId();
      quantity = type.startsWith("CART_") ? input.quantity() : null;
    } else if ("SEARCH".equals(type)) {
      String query = normalize(input.term());
      if (query.length() < 2 || query.length() > 40 || !query.matches("[a-z ]+")
          || keywords.stream().noneMatch(keyword -> keyword.contains(query))) return null;
      term = query;
    } else if ("CATEGORY".equals(type)) {
      term = catalog.stream().map(Dish::getCategoryName).filter(name -> Objects.equals(name, input.term()))
          .findFirst().orElse(null);
      if (term == null) return null;
    } else if ("ACTIVE".equals(type)) {
      if (input.seconds() == null) return null;
      seconds = input.seconds();
    } else if (!Set.of("PAGE_VIEW", "TABLE_MAP_VIEW", "CHAT_OPEN", "VOUCHER_VIEW", "VOUCHER_QUOTE").contains(type)
        || "TABLE_MAP_VIEW".equals(type) && !"booking".equals(input.page())) {
      return null;
    }
    return new Event("client:" + input.id(), type, input.page(), dishId, term, quantity, seconds, null, null, null);
  }

  public void chatMessage(HttpServletRequest request, Authentication auth) {
    safely(() -> {
      Actor actor = actor(request, auth);
      if (actor == null) return;
      locked(actor.browser(), () -> {
        LocalDateTime now = now();
        Visit visit = visit(actor, now);
        repository.update(visit.id(), actor.user(), now, visit.window(), visit.requests());
        repository.save(visit.id(), actor.kind(), now,
            new Event("chat:" + UUID.randomUUID(), "CHAT_MESSAGE", "chat", null, null, null, null, null, null, null));
        return null;
      });
    });
  }

  public void booking(HttpServletRequest request, Authentication auth, boolean selected, Reservation submitted) {
    booking(request, auth, selected, submitted, false);
  }

  public void booking(HttpServletRequest request, Authentication auth, boolean selected, Reservation submitted,
      boolean voucherApplied) {
    safely(() -> {
      Actor actor = actor(request, auth);
      if (actor == null) return;
      var session = request.getSession();
      synchronized (session) {
        LocalDateTime now = now();
        var current = session.getAttribute(FLOW);
        Flow flow = current instanceof Flow previous && previous.browser().equals(actor.browser())
            && previous.touched().plusMinutes(IDLE_MINUTES).isAfter(now)
            ? new Flow(previous.id(), actor.browser(), now) : new Flow(UUID.randomUUID().toString(), actor.browser(), now);
        locked(actor.browser(), () -> {
          Visit visit = visit(actor, now);
          repository.update(visit.id(), actor.user(), now, visit.window(), visit.requests());
          writeStage(visit.id(), actor.kind(), now, flow.id(), "BOOKING_START", null, null);
          if (selected || submitted != null) writeStage(visit.id(), actor.kind(), now, flow.id(), "TABLE_SELECTED", null, null);
          if (current instanceof Flow previous && previous.id().equals(flow.id()) && submitted == null) {
            String key = flow.id() + ":activity:" + now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            repository.save(visit.id(), actor.kind(), now,
                new Event(key, "BOOKING_ACTIVITY", "booking", null, null, null, null, flow.id(), null, null));
          }
          if (submitted != null) writeStage(visit.id(), actor.kind(), now, flow.id(), "BOOKING_SUBMITTED",
              submitted.getId(), submitted.getDepositAmount().signum() > 0);
          if (submitted != null && voucherApplied) writeStage(visit.id(), actor.kind(), now, flow.id(), "VOUCHER_APPLY", submitted.getId(), null);
          return null;
        });
        if (submitted == null) session.setAttribute(FLOW, flow);
        else session.removeAttribute(FLOW);
      }
    });
  }

  public void depositOpened(HttpServletRequest request, Authentication auth, long reservation) {
    safely(() -> {
      Actor actor = actor(request, auth);
      if (actor == null) return;
      locked(actor.browser(), () -> {
        if (!repository.ownsSubmission(actor.browser(), reservation)) return null;
        LocalDateTime now = now();
        Visit visit = visit(actor, now);
        repository.update(visit.id(), actor.user(), now, visit.window(), visit.requests());
        repository.save(visit.id(), actor.kind(), now,
            new Event("payment:" + reservation, "DEPOSIT_START", "confirmation",
                null, null, null, null, null, reservation, null));
        return null;
      });
    });
  }

  private void writeStage(String visit, String actor, LocalDateTime now, String flow, String type, Long reservation, Boolean deposit) {
    repository.save(visit, actor, now, new Event(flow + ":" + type, type, "booking",
        null, null, null, null, flow, reservation, deposit));
  }

  private Visit visit(Actor actor, LocalDateTime now) {
    var existing = repository.active(actor.browser()).orElse(null);
    if (existing != null) {
      if (existing.lastActivity().plusMinutes(IDLE_MINUTES).isAfter(now)
          && (existing.userId() == null || Objects.equals(existing.userId(), actor.user()))) return existing;
      repository.end(existing.id(), existing.lastActivity());
    }
    String id = UUID.randomUUID().toString();
    repository.start(id, actor.browser(), actor.user(), now);
    return new Visit(id, actor.user(), now, null, 0);
  }

  private Actor actor(HttpServletRequest request, Authentication auth) {
    if (!eligible(auth)) return null;
    String browser = browser(request);
    if (browser == null) return null;
    var user = accounts.signedInCustomer(auth);
    return new Actor(browser, user == null ? null : user.getId());
  }

  private <T> T locked(String browser, java.util.function.Supplier<T> action) {
    synchronized (locks[Math.floorMod(browser.hashCode(), locks.length)]) {
      return transactions.execute(status -> action.get());
    }
  }

  private void safely(Runnable action) {
    try { action.run(); }
    catch (DataAccessException | TransactionException error) {
      LOG.warn("Analytics observation could not be stored: {}", error.getClass().getSimpleName());
    }
  }

  @Scheduled(cron = "0 35 3 * * *", zone = "Asia/Ho_Chi_Minh")
  public void clean() {
    if (!enabled) return;
    LocalDateTime now = now();
    safely(() -> transactions.executeWithoutResult(status ->
        repository.clean(now.minusMinutes(IDLE_MINUTES), now.minusDays(RETENTION_DAYS))));
  }

  private String cookieValue(HttpServletRequest request) {
    if (request.getCookies() == null) return null;
    return Arrays.stream(request.getCookies()).filter(cookie -> COOKIE.equals(cookie.getName()))
        .map(jakarta.servlet.http.Cookie::getValue).findFirst().orElse(null);
  }

  private String browser(HttpServletRequest request) {
    String value = cookieValue(request);
    if (value == null || !value.matches("[0-9a-f-]{36}\\.[0-9]{10}\\.[A-Za-z0-9_-]{43}")) return null;
    if (Long.parseLong(value.substring(37,47)) <= Instant.now().getEpochSecond()) return null;
    return MessageDigest.isEqual(value.substring(48).getBytes(StandardCharsets.US_ASCII),
        signature(value.substring(0,47)).getBytes(StandardCharsets.US_ASCII)) ? value.substring(0,36) : null;
  }

  private String signature(String id) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("analytics:" + id).getBytes(StandardCharsets.US_ASCII)));
    } catch (java.security.GeneralSecurityException error) {
      throw new IllegalStateException("Analytics cookie signing unavailable", error);
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : java.text.Normalizer.normalize(value.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "").replace('đ', 'd').strip().replaceAll(" +", " ");
  }

  public static LocalDateTime now() { return LocalDateTime.now(ZONE); }
}
