package t3l.restaurant.t3l_restaurant.service;

import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ReservationConfirmationAccess {
  private static final String SESSION_KEY = ReservationConfirmationAccess.class.getName() + ".codes";
  private static final int MAX_RECEIPTS = 20;

  public void grant(HttpSession session, String code) {
    synchronized (session) {
      Set<String> codes = new LinkedHashSet<>();
      if (session.getAttribute(SESSION_KEY) instanceof Set<?> existing) {
        existing.stream().filter(String.class::isInstance).map(String.class::cast).forEach(codes::add);
      }
      codes.add(code);
      while (codes.size() > MAX_RECEIPTS) codes.remove(codes.iterator().next());
      session.setAttribute(SESSION_KEY, codes);
    }
  }

  public boolean canView(HttpSession session, String code) {
    if (session == null || code == null || !code.matches("T3L-[A-Z0-9-]{4,16}")) return false;
    synchronized (session) {
      return session.getAttribute(SESSION_KEY) instanceof Set<?> codes && codes.contains(code);
    }
  }
}
