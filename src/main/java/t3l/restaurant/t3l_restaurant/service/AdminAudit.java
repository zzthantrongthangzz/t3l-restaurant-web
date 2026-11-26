package t3l.restaurant.t3l_restaurant.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAudit {
  private final EmployeeActivityService activity;

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(String operation, String target) {
    activity.record(operation, "GENERAL", null, target, null);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(String operation, String targetType, Long targetId, String targetLabel, String details) {
    activity.record(operation, targetType, targetId, targetLabel, details);
  }
}
