package t3l.restaurant.t3l_restaurant.controller;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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
  public String history(@RequestParam(defaultValue = "") String code, @RequestParam(defaultValue = "") String operation,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "0") int page, Authentication actor, Model model) {
    code = code.strip();
    operation = operation.strip();
    boolean admin = actor.getAuthorities().stream().anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    model.addAttribute("rows", activities.myHistory(actor.getName(), code, operation, from, to, page));
    model.addAttribute("activityAdmin", admin);
    model.addAttribute("operations", activities.myOperations(actor.getName()));
    model.addAttribute("code", code);
    model.addAttribute("operation", operation);
    model.addAttribute("from", from);
    model.addAttribute("to", to);
    model.addAttribute("invalidRange", from != null && to != null && from.isAfter(to));
    model.addAttribute("filtered", !code.isEmpty() || !operation.isEmpty() || from != null || to != null);
    return "staff/activity";
  }
}
