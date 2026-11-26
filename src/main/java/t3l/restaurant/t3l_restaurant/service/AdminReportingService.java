package t3l.restaurant.t3l_restaurant.service;

import java.io.PrintWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.repository.ReservationChangeRequestRepository;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminReportingService {
  private final NamedParameterJdbcTemplate db;
  private final ReservationSchedule schedule;
  private final StaffWorkspaceService operations;
  private final ReservationChangeRequestRepository requests;

  private static final int PAGE_SIZE = 20;
  private static final String RECEIPTS = """
      WITH receipts AS (
        SELECT p.paid_at AS recorded_at, p.received_by AS employee, p.payment_method AS method, p.amount,
          CONCAT('T3L-HD-', o.id) AS reference_code, 'DINING' AS source_type, o.id AS invoice_id
        FROM dining_payments p JOIN dining_orders o ON o.id=p.order_id AND o.status='PAID'
        WHERE p.paid_at >= :start AND p.paid_at < :end AND (p.amount > 0 OR p.deposit_applied=0)
        UNION ALL
        SELECT p.paid_at, p.received_by, 'TRANSFER', p.deposit_applied,
          CONCAT('T3L-HD-',o.id, '-COC'), 'DEPOSIT', o.id
        FROM dining_payments p JOIN dining_orders o ON o.id=p.order_id AND o.status='PAID'
        WHERE p.paid_at >= :start AND p.paid_at < :end AND p.deposit_applied>0
        UNION ALL
        SELECT r.deposit_closed_at, '', 'TRANSFER', r.deposit_retained, CONCAT(r.code,'-COC'), 'DEPOSIT_RETAINED', NULL
        FROM reservations r
        WHERE r.deposit_closed_at >= :start AND r.deposit_closed_at < :end AND r.deposit_retained>0
        UNION ALL
        SELECT s.created_at, a.username, s.payment_method, s.total_amount, CONCAT('BL-',s.id), 'DIRECT', NULL
        FROM cash_sales s JOIN staff_shifts sh ON sh.id=s.shift_id JOIN app_users a ON a.id=sh.employee_id
        WHERE s.created_at >= :start AND s.created_at < :end
      )
      """;

  public record Filter(LocalDate from, LocalDate to, String employee, String method) {}
  public record PageRows<T>(List<T> content, long total, int page) {
    public boolean isPrevious() { return page > 0; }
    public boolean isNext() { return (page + 1L) * PAGE_SIZE < total; }
  }
  public record RevenueRow(String code, LocalDateTime time, String actor, String method, BigDecimal amount, String source) {}
  public record RevenueSummary(BigDecimal total, long dining, long direct, BigDecimal discount) {}
  public record InvoiceRow(Long id, LocalDateTime time, String bookingCode, String table, String status,
                           String employee, String method, BigDecimal amount) {
    public String getCode() { return "T3L-HD-" + id; }
    public String getStatusLabel() {
      return switch (status) {
        case "DRAFT" -> "Đang gọi món";
        case "FINALIZED" -> "Chờ thanh toán";
        case "PAID" -> "Đã thanh toán";
        case "VOID" -> "Đã đóng đơn trống";
        default -> status;
      };
    }
  }
  public record ShiftRow(Long id, String employee, LocalDateTime opened, LocalDateTime closed,
                        String status, BigDecimal counted, BigDecimal expected, BigDecimal difference) {}
  public record ChartPoint(String label, BigDecimal amount, int height) {}
  public record Bestseller(String name, long quantity) {}
  public record Activity(LocalDateTime time, String actor, String operation, String target) {}
  public record Dashboard(Filter filter, String grain, BigDecimal revenue, long bookings, long served,
                          long occupied, long pending, long cancellations, long noShows,
                          double cancellationRate, double noShowRate, List<ChartPoint> chart,
                          List<Bestseller> bestsellers, List<Activity> recent) {}

  public Filter filter(LocalDate from, LocalDate to, String employee, String method) {
    LocalDate end = to == null ? schedule.today() : to;
    LocalDate start = from == null ? end.minusDays(29) : from;
    if (end.getYear() > 9998 || start.getYear() < 1900 || start.isAfter(end) || start.plusDays(366).isBefore(end)) {
      throw new IllegalArgumentException("Khoảng thời gian báo cáo phải hợp lệ và tối đa 367 ngày.");
    }
    if (!List.of("", "CASH", "TRANSFER", "CARD").contains(method)) throw new IllegalArgumentException("Phương thức thanh toán không hợp lệ.");
    return new Filter(start, end, employee.strip(), method);
  }

  @Transactional(readOnly = true)
  public Dashboard dashboard(String grain) {
    LocalDate today = schedule.today();
    LocalDate from = switch (grain) {
      case "week" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(7);
      case "month" -> today.withDayOfMonth(1).minusMonths(11);
      default -> today.minusDays(13);
    };
    String scale = List.of("week", "month").contains(grain) ? grain : "day";
    var filter = new Filter(from, today, "", "");
    var args = parameters(filter);
    var stats = db.queryForMap("""
        SELECT COUNT(*) AS bookings,
          COALESCE(SUM(CASE WHEN status='CANCELLED' THEN 1 ELSE 0 END),0) AS cancelled,
          COALESCE(SUM(CASE WHEN status='NO_SHOW' THEN 1 ELSE 0 END),0) AS missed
        FROM reservations WHERE reservation_date >= :fromDay AND reservation_date <= :toDay
        """, args);
    long bookings = ((Number) stats.get("bookings")).longValue();
    long cancelled = ((Number) stats.get("cancelled")).longValue();
    long missed = ((Number) stats.get("missed")).longValue();
    Long served = db.queryForObject("""
        SELECT COALESCE(SUM(guest_count),0) FROM reservations
        WHERE status IN ('SEATED','COMPLETED') AND
          ((seated_at >= :start AND seated_at < :end) OR
           (seated_at IS NULL AND reservation_date >= :fromDay AND reservation_date <= :toDay))
        """, args, Long.class);
    var live = operations.dashboard();
    return new Dashboard(filter, scale, revenueSummary(filter).total(), bookings, served, live.serving(),
        live.pending() + live.changeRequests() + contactPending(), cancelled, missed, percent(cancelled, bookings),
        percent(missed, bookings), chart(filter, scale), bestsellers(args), recent());
  }

  private long contactPending() {
    return db.queryForObject("select count(*) from contact_messages where status in ('NEW','IN_PROGRESS')", Map.of(), Long.class);
  }

  private double percent(long part, long total) { return total == 0 ? 0 : 100d * part / total; }

  private List<ChartPoint> chart(Filter filter, String grain) {
    var sums = new HashMap<LocalDate, BigDecimal>();
    db.query(RECEIPTS + "SELECT CAST(recorded_at AS DATE) AS receipt_day, SUM(amount) AS total FROM receipts GROUP BY CAST(recorded_at AS DATE)",
        parameters(filter), row -> {
          LocalDate date = row.getObject("receipt_day", LocalDate.class);
          LocalDate bucket = switch (grain) {
            case "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> date.withDayOfMonth(1);
            default -> date;
          };
          sums.merge(bucket, row.getBigDecimal("total"), BigDecimal::add);
        });
    BigDecimal max = sums.values().stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    var values = new ArrayList<ChartPoint>();
    var format = DateTimeFormatter.ofPattern("month".equals(grain) ? "MM/yy" : "dd/MM");
    for (LocalDate date = filter.from(); !date.isAfter(filter.to()); date = switch (grain) {
      case "week" -> date.plusWeeks(1);
      case "month" -> date.plusMonths(1);
      default -> date.plusDays(1);
    }) {
      BigDecimal amount = sums.getOrDefault(date, BigDecimal.ZERO);
      int height = max.signum() > 0 ? amount.multiply(BigDecimal.valueOf(100)).divideToIntegralValue(max).intValue() : 0;
      values.add(new ChartPoint(date.format(format), amount, height));
    }
    return List.copyOf(values);
  }

  private List<Bestseller> bestsellers(MapSqlParameterSource args) {
    return db.query("""
        SELECT COALESCE(MAX(d.name), MAX(s.dish_name)) AS name, SUM(s.quantity) AS sold
        FROM (
          SELECT i.dish_id, i.dish_name, i.quantity FROM dining_order_items i
          JOIN dining_orders o ON o.id=i.order_id AND o.status='PAID'
          JOIN dining_payments p ON p.order_id=o.id
          WHERE i.cancelled=FALSE AND p.paid_at >= :start AND p.paid_at < :end
          UNION ALL
          SELECT dish_id,dish_name,quantity FROM cash_sales WHERE created_at >= :start AND created_at < :end
        ) s LEFT JOIN dishes d ON d.id=s.dish_id
        GROUP BY s.dish_id ORDER BY SUM(s.quantity) DESC, s.dish_id LIMIT 8
        """, args, (row, index) -> new Bestseller(row.getString("name"), row.getLong("sold")));
  }

  private List<Activity> recent() {
    return db.query("""
        SELECT happened, actor, operation, target FROM (
          SELECT occurred_at AS happened, actor, operation, target FROM admin_activity
            WHERE operation NOT IN ('Cập nhật đặt bàn','Xét duyệt yêu cầu')
          UNION ALL SELECT e.created_at, e.actor, e.description, r.code FROM reservation_events e
            JOIN reservations r ON r.id=e.reservation_id
          UNION ALL SELECT p.paid_at, p.received_by, 'Xác nhận thanh toán', CONCAT('T3L-HD-',p.order_id) FROM dining_payments p
          UNION ALL SELECT c.reviewed_at, c.reviewed_by,
              CASE c.request_type WHEN 'CANCEL' THEN 'Xử lý yêu cầu hủy bàn'
                WHEN 'PREORDER' THEN 'Xử lý yêu cầu đổi món' ELSE 'Xử lý yêu cầu đổi lịch' END, r.code
            FROM reservation_change_requests c JOIN reservations r ON r.id=c.reservation_id WHERE c.reviewed_at IS NOT NULL
          UNION ALL SELECT s.closed_at, a.username, 'Kết ca', CONCAT('Ca #',s.id)
            FROM staff_shifts s JOIN app_users a ON a.id=s.employee_id WHERE s.closed_at IS NOT NULL
          UNION ALL SELECT r.created_at, '', 'Tiếp nhận đặt bàn', r.code FROM reservations r
        ) items ORDER BY happened DESC, target DESC LIMIT 12
        """, Map.of(), (row, index) -> new Activity(row.getObject("happened", LocalDateTime.class),
        row.getString("actor"), row.getString("operation"), row.getString("target")));
  }

  @Transactional(readOnly = true)
  public PageRows<Activity> auditHistory(Filter filter, int page) {
    String criteria = " FROM admin_activity WHERE occurred_at >= :start AND occurred_at < :end AND (:employee='' OR actor=:employee)";
    return page("", criteria, "SELECT occurred_at,actor,operation,target", " ORDER BY occurred_at DESC,id DESC",
        paged(filter, page), page, (row, index) -> new Activity(row.getObject("occurred_at", LocalDateTime.class),
            row.getString("actor"), row.getString("operation"), row.getString("target")));
  }

  @Transactional(readOnly = true)
  public PageRows<RevenueRow> revenue(Filter filter, int page) {
    var args = paged(filter, page);
    String criteria = " FROM receipts WHERE (:employee='' OR employee=:employee) AND (:method='' OR method=:method)";
    return page(RECEIPTS, criteria, "SELECT reference_code,recorded_at,employee,method,amount,source_type",
        " ORDER BY recorded_at DESC,reference_code DESC", args, page, revenueRow());
  }

  @Transactional(readOnly = true)
  public RevenueSummary revenueSummary(Filter filter) {
    var params=parameters(filter);
    BigDecimal discount=db.queryForObject("""
        SELECT COALESCE(SUM(u.discount_amount),0)
        FROM voucher_usages u JOIN dining_payments p ON p.order_id=u.order_id
        JOIN dining_orders o ON o.id=p.order_id AND o.status='PAID'
        WHERE u.status='USED'
          AND p.paid_at>=:start AND p.paid_at<:end AND (:employee='' OR p.received_by=:employee)
          AND (:method='' OR (p.payment_method=:method AND (p.amount>0 OR p.deposit_applied=0))
            OR (:method='TRANSFER' AND p.deposit_applied>0))
        """,params,BigDecimal.class);
    return db.queryForObject(RECEIPTS + """
        SELECT COALESCE(SUM(amount),0) AS total,
          COUNT(DISTINCT invoice_id) AS dining,
          COALESCE(SUM(CASE WHEN source_type='DIRECT' THEN 1 ELSE 0 END),0) AS direct_sales
        FROM receipts WHERE (:employee='' OR employee=:employee) AND (:method='' OR method=:method)
        """,params,(row,index) -> new RevenueSummary(row.getBigDecimal("total"),row.getLong("dining"),row.getLong("direct_sales"),discount));
  }

  @Transactional(readOnly = true)
  public void exportRevenue(Filter filter, PrintWriter writer) {
    writer.print("\uFEFF");
    csv(writer, "Chứng từ", "Thời gian", "Nhân viên", "Phương thức", "Số tiền (VND)", "Nguồn");
    db.query(RECEIPTS + """
        SELECT reference_code,recorded_at,employee,method,amount,source_type FROM receipts
        WHERE (:employee='' OR employee=:employee) AND (:method='' OR method=:method)
        ORDER BY recorded_at DESC, reference_code DESC
        """, parameters(filter), row -> {
      var value = revenueRow().mapRow(row, 0);
      csv(writer, value.code(), value.time().toString(), value.actor(), value.method(),
          value.amount().toPlainString(), value.source());
    });
    writer.flush();
  }

  private void csv(PrintWriter writer, String... cells) {
    for (int index = 0; index < cells.length; index++) {
      if (index > 0) writer.print(',');
      String cell = cells[index] == null ? "" : cells[index];
      String control = cell.stripLeading();
      if ((!control.isEmpty() && "=+-@".indexOf(control.charAt(0)) >= 0)
          || cell.startsWith("\t") || cell.startsWith("\r") || cell.startsWith("\n")) cell = "'" + cell;
      writer.print('"' + cell.replace("\"", "\"\"") + '"');
    }
    writer.print("\r\n");
  }

  @Transactional(readOnly = true)
  public PageRows<InvoiceRow> invoices(Filter filter, String status, String code, int page) {
    if (!List.of("", "DRAFT", "FINALIZED", "PAID", "VOID").contains(status)) throw new IllegalArgumentException("Trạng thái đơn không hợp lệ.");
    var args = paged(filter, page).addValue("status", status).addValue("code", AdminInput.like(code));
    String criteria = """
         FROM dining_orders o JOIN reservations r ON r.id=o.reservation_id
         LEFT JOIN restaurant_tables t ON t.id=r.table_id LEFT JOIN dining_payments p ON p.order_id=o.id
         WHERE COALESCE(p.paid_at,o.opened_at) >= :start AND COALESCE(p.paid_at,o.opened_at) < :end
         AND (:employee='' OR COALESCE(p.received_by,o.opened_by)=:employee)
         AND (:method='' OR p.payment_method=:method OR (:method='TRANSFER' AND p.deposit_applied>0))
         AND (:status='' OR o.status=:status)
         AND (LOWER(r.code) LIKE :code ESCAPE '!' OR LOWER(CONCAT('T3L-HD-',o.id)) LIKE :code ESCAPE '!')
        """;
    return page("", criteria, """
        SELECT o.id, COALESCE(p.paid_at,o.opened_at) AS recorded_at, r.code, t.code AS table_code, o.status,
          COALESCE(p.received_by,o.opened_by) AS employee, p.payment_method, COALESCE(o.total_amount,p.amount) AS amount
        """, " ORDER BY recorded_at DESC, o.id DESC", args, page,
        (row, index) -> new InvoiceRow(row.getLong("id"), row.getObject("recorded_at", LocalDateTime.class), row.getString("code"),
            row.getString("table_code"), row.getString("status"), row.getString("employee"), row.getString("payment_method"), row.getBigDecimal("amount")));
  }

  @Transactional(readOnly = true)
  public PageRows<ShiftRow> shifts(Filter filter, String status, int page) {
    var args = paged(filter, page).addValue("status", status);
    String criteria = """
         FROM staff_shifts s JOIN app_users a ON a.id=s.employee_id
         WHERE s.opened_at >= :start AND s.opened_at < :end
         AND (:employee='' OR a.username=:employee) AND (:status='' OR s.status=:status)
        """;
    return page("", criteria, """
        SELECT s.id, a.username, s.opened_at, s.closed_at, s.status, s.counted_cash, s.cash_difference,
          s.opening_cash + CASE WHEN s.status='CLOSED' THEN COALESCE(s.cash_sales,0)
            ELSE COALESCE((SELECT SUM(p.amount) FROM dining_payments p
                           JOIN dining_orders o ON o.id=p.order_id AND o.status='PAID'
                           WHERE p.shift_id=s.id AND p.payment_method='CASH'),0)
               + COALESCE((SELECT SUM(c.total_amount) FROM cash_sales c
                           WHERE c.shift_id=s.id AND c.payment_method='CASH'),0) END AS expected
        """, " ORDER BY s.opened_at DESC,s.id DESC", args, page,
        (row, index) -> new ShiftRow(row.getLong("id"), row.getString("username"), row.getObject("opened_at", LocalDateTime.class),
            row.getObject("closed_at", LocalDateTime.class), row.getString("status"),
            row.getBigDecimal("counted_cash"), row.getBigDecimal("expected"), row.getBigDecimal("cash_difference")));
  }

  private MapSqlParameterSource parameters(Filter filter) {
    return new MapSqlParameterSource("start", filter.from().atStartOfDay())
        .addValue("end", filter.to().plusDays(1).atStartOfDay()).addValue("fromDay", filter.from()).addValue("toDay", filter.to())
        .addValue("employee", filter.employee()).addValue("method", filter.method());
  }

  private MapSqlParameterSource paged(Filter filter, int page) {
    return parameters(filter).addValue("limit", PAGE_SIZE).addValue("offset", Math.max(0, page) * (long) PAGE_SIZE);
  }

  private RowMapper<RevenueRow> revenueRow() {
    return (row, index) -> new RevenueRow(row.getString("reference_code"), row.getObject("recorded_at", LocalDateTime.class),
        row.getString("employee"), row.getString("method"), row.getBigDecimal("amount"), row.getString("source_type"));
  }

  private <T> PageRows<T> page(String prefix, String criteria, String select, String order,
      MapSqlParameterSource args, int page, RowMapper<T> mapper) {
    long total = db.queryForObject(prefix + "SELECT COUNT(*)" + criteria, args, Long.class);
    return new PageRows<>(db.query(prefix + select + criteria + order + " LIMIT :limit OFFSET :offset", args, mapper),
        total, Math.max(0, page));
  }
}
