package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import t3l.restaurant.t3l_restaurant.service.TrackingReportService;

@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAnalyticsController {
  private final TrackingReportService tracking;

  @GetMapping("/analytics")
  public String report(@RequestParam(defaultValue = "7") String period,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "") String actor, @RequestParam(defaultValue = "day") String grain, Model model) {
    model.addAttribute("report", tracking.report(tracking.filter(period, from, to, actor, grain)));
    return "admin/analytics";
  }

  @GetMapping("/analytics.csv")
  public void csv(@RequestParam(defaultValue = "7") String period,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "") String actor, @RequestParam(defaultValue = "day") String grain,
      HttpServletResponse response) throws IOException {
    var filter = tracking.filter(period, from, to, actor, grain);
    var report = tracking.report(filter);
    response.setContentType("text/csv;charset=UTF-8");
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"t3l-analytics-"
        + filter.from() + "-" + filter.to() + ".csv\"");
    tracking.export(report, response.getWriter());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public String invalid(IllegalArgumentException error, HttpServletResponse response, Model model) {
    response.setStatus(400);
    model.addAttribute("message", error.getMessage());
    return "admin/invalid";
  }
}
