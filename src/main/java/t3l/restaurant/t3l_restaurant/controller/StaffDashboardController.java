package t3l.restaurant.t3l_restaurant.controller;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import t3l.restaurant.t3l_restaurant.service.StaffShiftService;
import t3l.restaurant.t3l_restaurant.service.StaffWorkspaceService;

@Controller
@RequiredArgsConstructor
public class StaffDashboardController {
  private static final DateTimeFormatter VIETNAMESE_DATE =
      DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy", Locale.forLanguageTag("vi-VN"));
  private final StaffWorkspaceService workspace;
  private final StaffShiftService shifts;

  @GetMapping({"/staff", "/staff/dashboard"})
  public String dashboard(Authentication actor, Model model) {
    var shift = shifts.currentSummary(actor.getName());
    var dashboard = workspace.dashboard(shift != null);
    model.addAttribute("dashboard", dashboard);
    model.addAttribute("dashboardDate", VIETNAMESE_DATE.format(dashboard.date()));
    model.addAttribute("shift", shift);
    return "staff/dashboard";
  }
}
