package t3l.restaurant.t3l_restaurant.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.entity.AdminActivity;
import t3l.restaurant.t3l_restaurant.repository.AdminActivityRepository;

@Service
@RequiredArgsConstructor
public class AdminAudit {
  private final AdminActivityRepository activity;
  private final ReservationSchedule schedule;

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(String operation, String target) {
    var actor = SecurityContextHolder.getContext().getAuthentication();
    if (actor != null && actor.getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_ADMIN"))) {
      activity.save(new AdminActivity(actor.getName(), operation, target, LocalDateTime.now(schedule.getClock())));
    }
  }
}
