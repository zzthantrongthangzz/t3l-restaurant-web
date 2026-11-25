package t3l.restaurant.t3l_restaurant.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrackingRepository {
  public record Visit(String id, Long userId, LocalDateTime lastActivity, LocalDateTime window, int requests) {}
  public record Event(String key, String type, String page, Long dish, String term, Integer quantity,
                      Integer seconds, String flow, Long reservation, Boolean requiresDeposit) {}
  private final JdbcTemplate jdbc;

  public TrackingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public Optional<Visit> active(String browser) {
    return jdbc.query("""
        SELECT id,user_id,last_activity_at,request_window,request_count FROM tracking_sessions
        WHERE browser_id=? AND ended_at IS NULL ORDER BY last_activity_at DESC LIMIT 1 FOR UPDATE
        """, (rs, row) -> new Visit(rs.getString("id"), rs.getObject("user_id", Long.class),
        rs.getObject("last_activity_at", LocalDateTime.class), rs.getObject("request_window", LocalDateTime.class),
        rs.getInt("request_count")), browser).stream().findFirst();
  }

  public void start(String id, String browser, Long user, LocalDateTime now) {
    jdbc.update("""
        INSERT INTO tracking_sessions(id,browser_id,user_id,started_at,last_activity_at,request_count)
        VALUES (?,?,?,?,?,0)
        """, id, browser, user, now, now);
  }

  public void update(String id, Long user, LocalDateTime now, LocalDateTime window, int requests) {
    jdbc.update("""
        UPDATE tracking_sessions SET user_id=?, last_activity_at=?, request_window=?, request_count=? WHERE id=?
        """, user, now, window, requests, id);
  }

  public void end(String id, LocalDateTime end) {
    jdbc.update("UPDATE tracking_sessions SET ended_at=? WHERE id=?", end, id);
  }

  public void save(String visit, String actor, LocalDateTime now, Event event) {
    try {
      jdbc.update("""
          INSERT INTO tracking_events(session_id,event_key,event_type,occurred_at,actor_kind,page_key,
            dish_id,term,quantity,active_seconds,flow_key,reservation_id,requires_deposit)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
          """, visit, event.key(), event.type(), now, actor, event.page(), event.dish(), event.term(),
          event.quantity(), event.seconds(), event.flow(), event.reservation(), event.requiresDeposit());
    } catch (DuplicateKeyException retry) {
      // The same browser batch or booking stage may be delivered more than once.
    }
  }

  public boolean ownsSubmission(String browser, long reservation) {
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1
        FROM tracking_events e JOIN tracking_sessions s ON s.id=e.session_id
        WHERE e.reservation_id=? AND e.event_type='BOOKING_SUBMITTED' AND s.browser_id=?)
        """, Boolean.class, reservation, browser));
  }

  public void revoke(String browser, LocalDateTime now) {
    jdbc.update("UPDATE tracking_sessions SET ended_at=? WHERE browser_id=? AND ended_at IS NULL", now, browser);
  }

  public void clean(LocalDateTime idle, LocalDateTime retained) {
    jdbc.update("UPDATE tracking_sessions SET ended_at=last_activity_at WHERE ended_at IS NULL AND last_activity_at<?", idle);
    jdbc.update("DELETE FROM tracking_events WHERE occurred_at<?", retained);
    jdbc.update("DELETE FROM tracking_sessions WHERE last_activity_at<?", retained);
  }
}
