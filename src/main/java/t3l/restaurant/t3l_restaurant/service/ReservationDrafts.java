package t3l.restaurant.t3l_restaurant.service;

import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;

@Component
public class ReservationDrafts {
  private static final String KEY = ReservationDrafts.class.getName();

  public String save(HttpSession session, ReservationForm form, Long customerId, String existingId) {
    synchronized (session) {
      Map<String, Draft> entries = drafts(session);
      entries.values().removeIf(draft -> !draft.valid());
      Draft previous = entries.get(existingId);
      String id = previous != null && previous.belongsTo(customerId) ? existingId : UUID.randomUUID().toString();
      entries.put(id, new Draft(customerId, form.getDate(), form.getTime(), form.getGuests(),
          form.getPreferredArea(), form.getNote(), form.isConsentAccepted(), form.getTableId(),
          Instant.now().plusSeconds(1800)));
      while (entries.size() > 5) entries.remove(entries.keySet().iterator().next());
      session.setAttribute(KEY, entries);
      return id;
    }
  }

  public ReservationForm read(HttpSession session, String id, Long customerId) {
    if (session == null || id == null) return null;
    synchronized (session) {
      var entries = drafts(session);
      var draft = entries.get(id);
      if (draft == null || !draft.valid() || !draft.belongsTo(customerId)) return null;
      if (draft.customerId() == null && customerId != null) {
        entries.put(id, new Draft(customerId, draft.date(), draft.time(), draft.guests(), draft.area(), draft.note(),
            draft.consent(), draft.tableId(), draft.expires()));
        session.setAttribute(KEY, entries);
      }
      var form = new ReservationForm();
      form.setDate(draft.date());
      form.setTime(draft.time());
      form.setGuests(draft.guests());
      form.setPreferredArea(draft.area());
      form.setNote(draft.note());
      form.setConsentAccepted(draft.consent());
      form.setTableId(draft.tableId());
      return form;
    }
  }

  public void remove(HttpSession session, String id) {
    if (id == null) return;
    synchronized (session) {
      Map<String, Draft> entries = drafts(session);
      entries.remove(id);
      session.setAttribute(KEY, entries);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Draft> drafts(HttpSession session) {
    Object stored = session.getAttribute(KEY);
    return stored == null ? new LinkedHashMap<>() : (Map<String, Draft>) stored;
  }

  private record Draft(Long customerId, LocalDate date, LocalTime time, Integer guests, String area, String note,
      boolean consent, Long tableId, Instant expires) implements Serializable {
    boolean valid() { return Instant.now().isBefore(expires); }
    boolean belongsTo(Long customer) { return customerId == null || customerId.equals(customer); }
  }
}
