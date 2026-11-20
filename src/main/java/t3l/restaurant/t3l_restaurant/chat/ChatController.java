package t3l.restaurant.t3l_restaurant.chat;

import com.mongodb.MongoException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.service.BookingException;
import t3l.restaurant.t3l_restaurant.service.CustomerAccountService;
import t3l.restaurant.t3l_restaurant.service.ReservationDrafts;
import t3l.restaurant.t3l_restaurant.service.ReservationService;

@RestController
@RequestMapping("/chat")
class ChatController {
  private final ChatStore store;
  private final ChatSettings settings;
  private final ChatAssistant assistant;
  private final CustomerAccountService customers;
  private final ReservationDrafts drafts;
  private final ReservationService reservations;

  ChatController(ChatStore store, ChatSettings settings, ChatAssistant assistant, CustomerAccountService customers,
      ReservationDrafts drafts, ReservationService reservations) {
    this.store = store;
    this.settings = settings;
    this.assistant = assistant;
    this.customers = customers;
    this.drafts = drafts;
    this.reservations = reservations;
  }

  record Message(@NotBlank @Size(max = 800) String text) {}
  record Entry(String user, String reply) {}
  record SessionView(String csrfHeader, String csrfToken, List<Entry> history,
                     boolean conversationAvailable, boolean aiConfigured, boolean draftAvailable) {}
  private record Scope(String id, Long accountId) implements Serializable {}
  private static final String SCOPE = ChatController.class.getName();
  private static final String DRAFT_READY = SCOPE + ".draft";

  @GetMapping("/session")
  ResponseEntity<SessionView> session(HttpServletRequest request, Authentication auth, CsrfToken csrf) {
    var session = request.getSession();
    var scope = scope(session, customers.signedInCustomer(auth));
    try {
      var history = store.history(scope.id()).stream().map(turn -> new Entry(turn.user(), turn.reply())).toList();
      return response(HttpStatus.OK, new SessionView(csrf.getHeaderName(), csrf.getToken(), history,
          true, settings.configured(), !history.isEmpty() && scope.id().equals(session.getAttribute(DRAFT_READY))));
    } catch (MongoException | DataAccessException error) {
      return response(HttpStatus.OK, new SessionView(csrf.getHeaderName(), csrf.getToken(), List.of(), false,
          settings.configured(), false));
    }
  }

  @PostMapping(path = "/messages", consumes = "application/json")
  ResponseEntity<?> message(@Valid @RequestBody Message message, HttpServletRequest request, Authentication auth) {
    UserAccount customer = customers.signedInCustomer(auth);
    var session = request.getSession();
    var scope = scope(session, customer);
    var minute = Duration.ofMinutes(1);
    if (!store.allow("ip:" + ChatStore.hash(request.getRemoteAddr()), minute, settings.requestsPerMinute * 6)
        || !store.allow(customer == null ? "guest:" + scope.id() : "customer:" + customer.getId(),
            minute, settings.requestsPerMinute)) return limited();
    synchronized (session) {
      var result = assistant.reply(message.text().strip(), store.history(scope.id()), customer);
      store.append(scope.id(), result.turn());
      session.setAttribute(DRAFT_READY, result.reply().draft() ? scope.id() : "");
      return response(HttpStatus.OK, result.reply());
    }
  }

  @PostMapping("/booking-draft")
  ResponseEntity<?> draft(HttpServletRequest request, Authentication auth) {
    var session = request.getSession();
    var scope = scope(session, customers.signedInCustomer(auth));
    if (!store.allow("draft:" + scope.id(), Duration.ofMinutes(1), 5)) return limited();
    synchronized (session) {
      var history = store.history(scope.id());
      var intent = history.isEmpty() ? null : ChatIntent.stored(history.getLast().context());
      if (!scope.id().equals(session.getAttribute(DRAFT_READY)) || intent == null || !ChatAssistant.complete(intent)
          || (intent.kind() != ChatIntent.Kind.AVAILABILITY && intent.kind() != ChatIntent.Kind.DRAFT)
          || intent.guests() < 1 || intent.guests() > 20) {
        return response(HttpStatus.CONFLICT, Map.of("error", "Bạn hãy kiểm tra lại ngày, giờ và số khách trước."));
      }
      var form = ChatAssistant.form(intent);
      reservations.checkAvailability(form);
      String draft = drafts.save(session, form, scope.accountId(), null);
      session.removeAttribute(DRAFT_READY);
      return response(HttpStatus.OK, Map.of("url", request.getContextPath() + "/reservations/new?draft=" + draft));
    }
  }

  @DeleteMapping("/session")
  ResponseEntity<?> clear(HttpServletRequest request, Authentication auth) {
    var session = request.getSession();
    var scope = scope(session, customers.signedInCustomer(auth));
    synchronized (session) {
      store.clear(scope.id());
      session.removeAttribute(DRAFT_READY);
    }
    return response(HttpStatus.OK, Map.of());
  }

  private Scope scope(HttpSession session, UserAccount customer) {
    Long account = customer == null ? null : customer.getId();
    synchronized (session) {
      if (session.getAttribute(SCOPE) instanceof Scope current && Objects.equals(current.accountId(), account)) {
        return current;
      }
      var scope = new Scope(UUID.randomUUID().toString(), account);
      session.setAttribute(SCOPE, scope);
      session.removeAttribute(DRAFT_READY);
      return scope;
    }
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  ResponseEntity<?> invalid() {
    return response(HttpStatus.BAD_REQUEST, Map.of("error", "Bạn hãy nhập tin nhắn từ 1 đến 800 ký tự."));
  }

  @ExceptionHandler({MongoException.class, DataAccessException.class})
  ResponseEntity<?> unavailable() {
    return response(HttpStatus.SERVICE_UNAVAILABLE, Map.of("error", "Trợ lý đang gián đoạn. Bạn vui lòng thử lại sau."));
  }

  @ExceptionHandler(BookingException.class)
  ResponseEntity<?> bookingError(BookingException error) {
    return response(HttpStatus.CONFLICT, Map.of("error", error.getMessage()));
  }

  private static ResponseEntity<?> limited() {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).cacheControl(CacheControl.noStore())
        .header("Retry-After", "60").body(Map.of("error", "Bạn đã gửi nhiều tin nhắn. Vui lòng thử lại sau một phút."));
  }

  private static <T> ResponseEntity<T> response(HttpStatus status, T body) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
  }
}
