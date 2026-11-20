package t3l.restaurant.t3l_restaurant.controller;

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
  private final StaffWorkspaceService workspace;
  private final StaffShiftService shifts;

  @GetMapping({"/staff", "/staff/dashboard"})
  public String dashboard(Authentication actor, Model model) {
    model.addAttribute("dashboard", workspace.dashboard());
    model.addAttribute("openShift", shifts.current(actor.getName()) != null);
    return "staff/dashboard";
  }
}
