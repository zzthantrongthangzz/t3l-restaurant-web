package t3l.restaurant.t3l_restaurant.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.entity.EmployeeActivity;
import t3l.restaurant.t3l_restaurant.repository.EmployeeActivityRepository;
import t3l.restaurant.t3l_restaurant.repository.EmployeeActivityRepository.HistoryEntry;

@Service
@RequiredArgsConstructor
public class EmployeeActivityService {
  private static final int PAGE_SIZE = 20;

  private final EmployeeActivityRepository activities;
  private final ReservationSchedule schedule;

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(String operation, String targetType, Long targetId, String targetLabel, String details) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    String role = employeeRole(authentication);
    if (role == null) return;

    String actor = clean(authentication.getName(), 80);
    if (actor.isBlank()) return;
    activities.save(new EmployeeActivity(actor, role, clean(operation, 80), cleanType(targetType), targetId,
        clean(targetLabel, 250), clean(details, 500), LocalDateTime.now(schedule.getClock())));
  }

  @Transactional(readOnly = true)
  public Page<HistoryEntry> myHistory(String actor, String code, String operation,
      LocalDate from, LocalDate to, int page) {
    var request = PageRequest.of(Math.clamp(page, 0, Integer.MAX_VALUE / PAGE_SIZE), PAGE_SIZE);
    if (from != null && to != null && from.isAfter(to)) return Page.empty(request.first());
    return activities.findHistory(actor, code.toLowerCase(Locale.ROOT), operation,
        from == null ? null : from.atStartOfDay(),
        to == null ? null : to.plusDays(1).atStartOfDay(), request);
  }

  @Transactional(readOnly = true)
  public List<String> myOperations(String actor) {
    return activities.findOperationsByActor(actor);
  }

  private String employeeRole(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) return null;
    for (GrantedAuthority authority : authentication.getAuthorities()) {
      String value = authority.getAuthority();
      if ("ROLE_ADMIN".equals(value)) return "ADMIN";
      if ("ROLE_STAFF".equals(value)) return "STAFF";
    }
    return null;
  }

  private String cleanType(String value) {
    if (value == null || value.isBlank()) return "GENERAL";
    String type = value.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    return clean(type, 32);
  }

  private String clean(String value, int maxLength) {
    if (value == null) return "";
    String cleaned = value.replaceAll("[\\p{Cntrl}]", " ").strip();
    return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
  }
}
