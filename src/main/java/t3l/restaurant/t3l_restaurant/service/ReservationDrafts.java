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
          form.getPreorderDishIds() == null ? java.util.List.of()
              : form.getPreorderDishIds().stream().filter(java.util.Objects::nonNull).toList(),
          form.getPreorderQuantities() == null ? Map.of() : new LinkedHashMap<>(form.getPreorderQuantities()),
          bounded(form.getPreorderNote(), 500), bounded(form.getVoucherCode(), 32),
          bounded(form.getGuestName(), 120), bounded(form.getGuestPhone(), 30), bounded(form.getGuestEmail(), 254),
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
            draft.consent(), draft.tableId(), draft.preorderDishIds(), draft.preorderQuantities(), draft.preorderNote(), draft.voucherCode(),
            draft.guestName(), draft.guestPhone(),
            draft.guestEmail(), draft.expires()));
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
      form.setPreorderDishIds(new java.util.ArrayList<>(draft.preorderDishIds()));
      if (draft.preorderQuantities() != null) form.setPreorderQuantities(new LinkedHashMap<>(draft.preorderQuantities()));
      form.setPreorderNote(draft.preorderNote());
      form.setVoucherCode(draft.voucherCode());
      form.setGuestName(draft.guestName());
      form.setGuestPhone(draft.guestPhone());
      form.setGuestEmail(draft.guestEmail());
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

  private String bounded(String value, int length) {
    return value != null && value.length() <= length ? value : null;
  }

  private record Draft(Long customerId, LocalDate date, LocalTime time, Integer guests, String area, String note,
      boolean consent, Long tableId, java.util.List<Long> preorderDishIds, Map<Long, Integer> preorderQuantities, String preorderNote, String voucherCode,
      String guestName, String guestPhone,
      String guestEmail, Instant expires) implements Serializable {
    boolean valid() { return Instant.now().isBefore(expires); }
    boolean belongsTo(Long customer) { return customerId == null || customerId.equals(customer); }
  }
}
