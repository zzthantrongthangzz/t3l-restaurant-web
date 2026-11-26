package t3l.restaurant.t3l_restaurant.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import t3l.restaurant.t3l_restaurant.service.EmployeeActivityService;

@Controller
@RequestMapping("/staff/activity")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
public class EmployeeActivityController {
  private final EmployeeActivityService activities;

  @GetMapping
  public String history(@RequestParam(defaultValue = "0") int page, Authentication actor, Model model) {
    model.addAttribute("rows", activities.myHistory(actor.getName(), Math.max(0, page)));
    return "staff/activity";
  }
}
