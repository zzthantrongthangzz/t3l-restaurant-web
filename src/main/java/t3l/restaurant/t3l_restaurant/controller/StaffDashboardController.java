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
    var dashboard = workspace.dashboard();
    boolean shiftOpen = shifts.current(actor.getName()) != null;
    var actions = java.util.stream.Stream.concat(dashboard.toConfirm().stream(), dashboard.checkedIn().stream()).distinct()
        .collect(java.util.stream.Collectors.toMap(booking -> booking.getId(), booking -> workspace.actions(booking, shiftOpen)));
    model.addAttribute("dashboard", dashboard);
    model.addAttribute("bookingActions", actions);
    model.addAttribute("openShift", shiftOpen);
    return "staff/dashboard";
  }
}
