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

  static String withReason(String details, String reason) {
    return reason == null || reason.isBlank() ? details : details + " Lý do: " + reason.strip();
  }

  static String displayDetails(String operation, String details) {
    if (details == null || !details.startsWith("Trước: hoạt động=")) return details;
    String message = switch (operation) {
      case "Khóa tài khoản" -> "Đã khóa tài khoản và kết thúc các phiên đăng nhập hiện tại.";
      case "Mở khóa tài khoản" -> "Đã mở khóa tài khoản. Người dùng có thể đăng nhập lại.";
      default -> null;
    };
    if (message == null) return details;
    String separator = "; lý do: ";
    int marker = details.indexOf(separator);
    String reason = marker < 0 ? null : details.substring(marker + separator.length());
    return withReason(message, "không cung cấp".equals(reason) ? null : reason);
  }
}
