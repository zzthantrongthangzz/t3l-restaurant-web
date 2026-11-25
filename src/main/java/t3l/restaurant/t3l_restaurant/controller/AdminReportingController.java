package t3l.restaurant.t3l_restaurant.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import t3l.restaurant.t3l_restaurant.dto.AdminReportFilter;
import t3l.restaurant.t3l_restaurant.repository.DiningOrderRepository;
import t3l.restaurant.t3l_restaurant.service.AdminReportingService;
import t3l.restaurant.t3l_restaurant.service.DiningOrderService;
import t3l.restaurant.t3l_restaurant.service.TrackingReportService;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminReportingController {
  private final AdminReportingService reports;
  private final TrackingReportService tracking;
  private final DiningOrderRepository invoices;
  private final DiningOrderService orders;

  @GetMapping({"", "/dashboard"})
  public String dashboard(@RequestParam(defaultValue = "day") String period, Model model) {
    model.addAttribute("dashboard", reports.dashboard(period));
    model.addAttribute("traffic", tracking.summary(tracking.filter("today", null, null, "", "day")));
    return "admin/dashboard";
  }

  @GetMapping("/invoices")
  public String invoices(@ModelAttribute("query") AdminReportFilter query, Model model) {
    var filter = filter(query);
    model.addAttribute("filter", filter);
    model.addAttribute("rows", reports.invoices(filter, query.getStatus(), query.getCode(), query.getPage()));
    return "admin/invoices";
  }

  @GetMapping("/invoices/{id}")
  public String invoice(@PathVariable Long id, Model model) {
    Long reservationId = invoices.findReservationId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    var view = orders.view(reservationId);
    if (view == null || view.order() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    model.addAttribute("view", view);
    return "admin/invoice";
  }

  @GetMapping("/revenue")
  public String revenue(@ModelAttribute("query") AdminReportFilter query, Model model) {
    var filter = filter(query);
    model.addAttribute("filter", filter);
    model.addAttribute("rows", reports.revenue(filter, query.getPage()));
    model.addAttribute("summary", reports.revenueSummary(filter));
    return "admin/revenue";
  }

  @GetMapping("/revenue.csv")
  public void csv(@ModelAttribute AdminReportFilter query, HttpServletResponse response) throws IOException {
    var filter = filter(query);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType("text/csv");
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
        "attachment; filename=\"t3l-revenue-" + filter.from() + "-" + filter.to() + ".csv\"");
    reports.exportRevenue(filter, response.getWriter());
  }

  @GetMapping("/shifts")
  public String shifts(@ModelAttribute("query") AdminReportFilter query, Model model) {
    var filter = filter(query);
    model.addAttribute("filter", filter);
    model.addAttribute("rows", reports.shifts(filter, query.getStatus(), query.getPage()));
    return "admin/shifts";
  }

  @GetMapping("/activity")
  public String activity(@ModelAttribute("query") AdminReportFilter query, Model model) {
    var filter = filter(query);
    model.addAttribute("filter", filter);
    model.addAttribute("rows", reports.auditHistory(filter, query.getPage()));
    return "admin/activity";
  }

  private AdminReportingService.Filter filter(AdminReportFilter query) {
    return reports.filter(query.getFrom(), query.getTo(), query.getEmployee(), query.getMethod());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public String invalid(IllegalArgumentException error, HttpServletResponse response, Model model) {
    response.setStatus(HttpStatus.BAD_REQUEST.value());
    model.addAttribute("message", error.getMessage());
    return "admin/invalid";
  }
}
