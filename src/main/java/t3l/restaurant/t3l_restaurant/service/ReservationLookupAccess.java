package t3l.restaurant.t3l_restaurant.service;

import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ReservationLookupAccess {
  private static final String KEY = ReservationLookupAccess.class.getName();
  private final ReservationSchedule schedule;
  public ReservationLookupAccess(ReservationSchedule schedule) { this.schedule = schedule; }

  public void grant(HttpSession session, String code) {
    synchronized (session) {
      Map<String, Long> entries = entries(session);
      entries.entrySet().removeIf(entry -> entry.getValue() <= schedule.getClock().millis());
      entries.put(code, schedule.getClock().millis() + 30 * 60_000L);
      while (entries.size() > 20) entries.remove(entries.keySet().iterator().next());
      session.setAttribute(KEY, entries);
    }
  }

  public boolean canView(HttpSession session, String code) {
    if (session == null || code == null || !code.matches("T3L-[A-Z0-9-]{4,16}")) return false;
    synchronized (session) {
      return entries(session).getOrDefault(code, 0L) > schedule.getClock().millis();
    }
  }

  public void revoke(HttpSession session, String code) {
    synchronized (session) {
      Map<String, Long> entries = entries(session);
      entries.remove(code);
      session.setAttribute(KEY, entries);
    }
  }

  private Map<String, Long> entries(HttpSession session) {
    Map<String, Long> result = new LinkedHashMap<>();
    if (session.getAttribute(KEY) instanceof Map<?, ?> stored) {
      stored.forEach((key, value) -> {
        if (key instanceof String name && value instanceof Long expires) result.put(name, expires);
      });
    }
    return result;
  }
}
