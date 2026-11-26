package t3l.restaurant.t3l_restaurant.service;

import java.io.PrintWriter;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackingReportService {
  private static final String EVENTS = """
      FROM tracking_events e JOIN tracking_sessions s ON s.id=e.session_id
      WHERE e.occurred_at>=:start AND e.occurred_at<:end AND (:actor='' OR e.actor_kind=:actor)
      """;
  private static final String FUNNEL = """
      SELECT p.session_id,p.selected,p.reservation_id,p.required,p.last_step,
        CASE WHEN r.id IS NOT NULL AND r.deposit_paid > 0 AND r.deposit_paid >= r.deposit_amount THEN 1 ELSE 0 END AS paid,
        CASE WHEN r.id IS NOT NULL AND (r.status IN ('CONFIRMED','CHECKED_IN','SEATED','COMPLETED','NO_SHOW')
          OR EXISTS (SELECT 1 FROM reservation_events h WHERE h.reservation_id=r.id AND h.description='Đã xác nhận.'))
          THEN 1 ELSE 0 END AS confirmed,
        CASE WHEN EXISTS (SELECT 1 FROM tracking_events pay WHERE pay.reservation_id=r.id AND pay.event_type='DEPOSIT_START') THEN 1 ELSE 0 END AS payment_started
      FROM (
        SELECT a.flow_key,a.session_id,MAX(e.occurred_at) AS last_step,
          MAX(CASE WHEN e.event_type='TABLE_SELECTED' THEN 1 ELSE 0 END) AS selected,
          MAX(CASE WHEN e.event_type='BOOKING_SUBMITTED' THEN e.reservation_id ELSE NULL END) AS reservation_id,
          MAX(CASE WHEN e.event_type='BOOKING_SUBMITTED' AND e.requires_deposit=TRUE THEN 1 ELSE 0 END) AS required
        FROM tracking_events a JOIN tracking_events e ON e.flow_key=a.flow_key
        WHERE a.event_type='BOOKING_START' AND a.occurred_at>=:start AND a.occurred_at<:end
          AND (:actor='' OR a.actor_kind=:actor)
        GROUP BY a.flow_key,a.session_id
      ) p LEFT JOIN reservations r ON r.id=p.reservation_id
      WHERE COALESCE(r.deposit_demo,FALSE)=FALSE
      """;

  public record Filter(LocalDate from, LocalDate to, String preset, String actor, String grain) {}
  public record Summary(long views, long visitors, long sessions, long activeSeconds, long activeAverage,
                        double conversion, double abandonment, long abandoned) {}
  public record Metric(String label, long count, long secondary) {
    public Metric(String label, long count) { this(label, count, 0); }
  }
  public record Step(String label, long count, double rate) {}
  public record Branch(String label, List<Step> steps) {}
  public record Report(Filter filter, Summary summary, List<Metric> series, long seriesMax, List<Metric> audiences,
                       List<Metric> pages, List<Metric> dishes, List<Metric> searches, List<Metric> categories,
                       List<Metric> hours, List<Metric> chat, List<Metric> cart, List<Metric> behaviors, List<Step> funnel, List<Branch> branches) {}
  private record Totals(long views, long visitors, long sessions, long active) {}
  private record Flow(String session, boolean selected, Long reservation, boolean required, boolean paid,
                      boolean confirmed, boolean paymentStarted, LocalDateTime lastStep) {}
  private record Cohort(Totals totals, Set<String> traffic, List<Flow> flows, long abandoned, long resolved) {}

  private final NamedParameterJdbcTemplate jdbc;
  public TrackingReportService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

  public Filter filter(String preset, LocalDate from, LocalDate to, String actor, String grain) {
    LocalDate today = TrackingService.now().toLocalDate();
    preset = Set.of("today", "7", "30", "custom").contains(Objects.toString(preset, "")) ? preset : "7";
    actor = Set.of("GUEST", "BUYER").contains(Objects.toString(actor, "")) ? actor : "";
    grain = Set.of("day", "week", "month").contains(Objects.toString(grain, "")) ? grain : "day";
    if (!"custom".equals(preset)) {
      to = today;
      from = today.minusDays("today".equals(preset) ? 0 : Integer.parseInt(preset) - 1L);
    }
    if (from == null || to == null || from.isAfter(to) || to.isAfter(today)
        || from.isBefore(today.minusDays(TrackingService.RETENTION_DAYS - 1))) {
      throw new IllegalArgumentException("Chọn khoảng ngày hợp lệ trong 90 ngày gần nhất.");
    }
    return new Filter(from, to, preset, actor, grain);
  }

  @Transactional(readOnly = true)
  public Summary summary(Filter filter) { return summary(cohort(params(filter))); }

  @Transactional(readOnly = true)
  public Report report(Filter filter) {
    var params = params(filter);
    var cohort = cohort(params);
    var series = series(filter, params);
    var audience = jdbc.query("SELECT e.actor_kind,COUNT(*) " + EVENTS
        + " AND e.event_type='PAGE_VIEW' GROUP BY e.actor_kind ORDER BY e.actor_kind",
        params, (rs, row) -> new Metric(label(rs.getString(1)), rs.getLong(2)));
    var pages = jdbc.query("""
        SELECT e.page_key,SUM(CASE WHEN e.event_type='PAGE_VIEW' THEN 1 ELSE 0 END),
          COALESCE(SUM(e.active_seconds),0)
        """ + EVENTS + " AND e.event_type IN ('PAGE_VIEW','ACTIVE') GROUP BY e.page_key ORDER BY 2 DESC,e.page_key LIMIT 15",
        params, (rs,row) -> new Metric(label(rs.getString(1)),rs.getLong(2),rs.getLong(3)));
    var dishes = jdbc.query("""
        SELECT COALESCE(d.name,'Món đã xóa'),
          SUM(CASE WHEN e.event_type='DISH_VIEW' THEN 1 ELSE 0 END),
          SUM(CASE WHEN e.event_type='DISH_CLICK' THEN 1 ELSE 0 END)
        FROM tracking_events e LEFT JOIN dishes d ON d.id=e.dish_id
        WHERE e.occurred_at>=:start AND e.occurred_at<:end AND (:actor='' OR e.actor_kind=:actor)
          AND e.event_type IN ('DISH_VIEW','DISH_CLICK')
        GROUP BY e.dish_id,d.name ORDER BY (SUM(CASE WHEN e.event_type='DISH_VIEW' THEN 1 ELSE 0 END)
          + SUM(CASE WHEN e.event_type='DISH_CLICK' THEN 1 ELSE 0 END)) DESC,1 LIMIT 10
        """, params, (rs,row) -> new Metric(rs.getString(1),rs.getLong(2),rs.getLong(3)));
    var searches = topTerms(params, "SEARCH");
    var categories = topTerms(params, "CATEGORY");
    var hours = jdbc.query("SELECT EXTRACT(HOUR FROM e.occurred_at),COUNT(*) " + EVENTS
        + " AND e.event_type='PAGE_VIEW' GROUP BY EXTRACT(HOUR FROM e.occurred_at) ORDER BY 2 DESC,1",
        params, (rs,row) -> new Metric("%02d:00".formatted(rs.getInt(1)),rs.getLong(2)));
    var chat = eventCounts(params, "('CHAT_OPEN','CHAT_MESSAGE')");
    var cart = eventCounts(params, "('CART_ADD','CART_REMOVE','CART_QUANTITY')");
    var behaviors = new ArrayList<>(eventCounts(params, "('TABLE_MAP_VIEW','VOUCHER_VIEW','VOUCHER_QUOTE','VOUCHER_APPLY')"));
    Long voucherUsed=jdbc.queryForObject("""
        SELECT COUNT(*) FROM voucher_usages u JOIN dining_orders o ON o.id=u.order_id
        WHERE u.status='USED' AND u.demo=FALSE AND u.closed_at>=:start AND u.closed_at<:end
          AND EXISTS (SELECT 1 FROM tracking_events e WHERE e.reservation_id=o.reservation_id
            AND e.event_type='BOOKING_SUBMITTED' AND (:actor='' OR e.actor_kind=:actor))
        """,params,Long.class);
    behaviors.add(new Metric("Voucher đã dùng",voucherUsed));
    var flows = cohort.flows();
    behaviors.add(new Metric("Bắt đầu thanh toán cọc", flows.stream().filter(f -> f.paymentStarted() || f.paid()).count()));
    behaviors.add(new Metric("Hoàn tất thanh toán cọc", flows.stream().filter(Flow::paid).count()));
    var funnel = new ArrayList<Step>();
    add(funnel, "Truy cập website", cohort.traffic().size());
    add(funnel, "Bắt đầu đặt bàn", counted(cohort, f -> true));
    add(funnel, "Đã xếp bàn", counted(cohort, Flow::selected));
    add(funnel, "Gửi yêu cầu đặt bàn", counted(cohort, f -> f.reservation() != null));
    add(funnel, "Đạt điều kiện cọc", counted(cohort, f -> f.reservation() != null && (!f.required() || f.paid())));
    add(funnel, "Đặt bàn được xác nhận", counted(cohort, f -> f.confirmed() && (!f.required() || f.paid())));
    return new Report(filter, summary(cohort), series, series.stream().mapToLong(Metric::count).max().orElse(1), audience, pages, dishes, searches, categories, hours, chat, cart, behaviors,
        funnel, List.of(branch("Cần cọc", flows, true), branch("Không cần cọc", flows, false)));
  }

  private Cohort cohort(Map<String,?> params) {
    Totals totals = jdbc.queryForObject("""
        SELECT COALESCE(SUM(CASE WHEN e.event_type='PAGE_VIEW' THEN 1 ELSE 0 END),0),
          COUNT(DISTINCT CASE WHEN e.event_type='PAGE_VIEW' THEN COALESCE(CONCAT('u:',s.user_id),CONCAT('b:',s.browser_id)) END),
          COUNT(DISTINCT e.session_id),COALESCE(SUM(e.active_seconds),0)
        """ + EVENTS, params, (rs,row) -> new Totals(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4)));
    var traffic = new java.util.HashSet<>(jdbc.query("SELECT DISTINCT e.session_id " + EVENTS
        + " AND e.event_type='PAGE_VIEW'", params, (rs,row) -> rs.getString(1)));
    var flows = jdbc.query(FUNNEL, params, (rs,row) -> new Flow(rs.getString("session_id"),
        rs.getBoolean("selected"),rs.getObject("reservation_id",Long.class),rs.getBoolean("required"),rs.getBoolean("paid"),
        rs.getBoolean("confirmed"),rs.getBoolean("payment_started"),rs.getObject("last_step",LocalDateTime.class)));
    var cutoff = TrackingService.now().minusMinutes(TrackingService.IDLE_MINUTES);
    long abandoned = flows.stream().filter(flow -> flow.reservation() == null && !flow.lastStep().isAfter(cutoff)).count();
    long resolved = flows.stream().filter(flow -> flow.reservation() != null).count() + abandoned;
    return new Cohort(totals, traffic, flows, abandoned, resolved);
  }

  private Summary summary(Cohort cohort) {
    var t = cohort.totals();
    return new Summary(t.views(),t.visitors(),t.sessions(),t.active(), t.sessions()==0 ? 0 : Math.round((double)t.active()/t.sessions()),
        percent(counted(cohort, f -> f.confirmed() && (!f.required() || f.paid())), cohort.traffic().size()), percent(cohort.abandoned(),cohort.resolved()),cohort.abandoned());
  }

  private long counted(Cohort cohort, java.util.function.Predicate<Flow> test) {
    return cohort.flows().stream().filter(test).map(Flow::session).filter(cohort.traffic()::contains).distinct().count();
  }

  private Branch branch(String title, List<Flow> flows, boolean deposit) {
    var matching = flows.stream().filter(flow -> flow.reservation()!=null && flow.required()==deposit).toList();
    var steps = new ArrayList<Step>();
    add(steps, "Gửi yêu cầu", matching.size());
    if (deposit) {
      add(steps, "Bắt đầu thanh toán", matching.stream().filter(flow -> flow.paymentStarted() || flow.paid()).count());
      add(steps, "Hoàn tất cọc", matching.stream().filter(Flow::paid).count());
    }
    add(steps, "Được xác nhận", matching.stream().filter(flow -> flow.confirmed() && (!deposit || flow.paid())).count());
    return new Branch(title, steps);
  }

  private void add(List<Step> steps, String label, long count) {
    steps.add(new Step(label, count, steps.isEmpty() ? percent(count,count) : percent(count,steps.getLast().count())));
  }
  private double percent(long count, long denominator) {
    return denominator == 0 ? 0 : Math.round(count * 1000d / denominator) / 10d;
  }

  private List<Metric> series(Filter filter, Map<String,?> params) {
    Map<LocalDate,Long> daily = new HashMap<>();
    jdbc.query("SELECT CAST(e.occurred_at AS DATE),COUNT(*) " + EVENTS
        + " AND e.event_type='PAGE_VIEW' GROUP BY CAST(e.occurred_at AS DATE)", params, rs -> {
      daily.put(rs.getObject(1,LocalDate.class),rs.getLong(2));
    });
    Map<LocalDate,Long> buckets = new LinkedHashMap<>();
    for (LocalDate date = filter.from(); !date.isAfter(filter.to()); date = date.plusDays(1)) {
      LocalDate bucket = switch (filter.grain()) {
        case "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        case "month" -> date.withDayOfMonth(1);
        default -> date;
      };
      buckets.merge(bucket,daily.getOrDefault(date,0L),Long::sum);
    }
    return buckets.entrySet().stream().map(entry -> new Metric(entry.getKey().toString(),entry.getValue())).toList();
  }

  private List<Metric> topTerms(Map<String,?> params, String type) {
    var filtered = new HashMap<String,Object>(params);
    filtered.put("metric",type);
    return jdbc.query("SELECT e.term,COUNT(*) " + EVENTS
        + " AND e.event_type=:metric AND e.term IS NOT NULL GROUP BY e.term ORDER BY 2 DESC,e.term LIMIT 10",
        filtered,(rs,row)->new Metric(rs.getString(1),rs.getLong(2)));
  }

  private List<Metric> eventCounts(Map<String,?> params, String events) {
    return jdbc.query("SELECT e.event_type,COUNT(*) " + EVENTS + " AND e.event_type IN " + events
        + " GROUP BY e.event_type ORDER BY e.event_type",params,(rs,row)->new Metric(label(rs.getString(1)),rs.getLong(2)));
  }

  private String label(String value) {
    return switch (value) {
      case "GUEST" -> "Khách vãng lai";
      case "BUYER" -> "Khách đã đăng nhập";
      case "home" -> "Trang chủ";
      case "menu" -> "Thực đơn";
      case "about" -> "Giới thiệu";
      case "contact" -> "Liên hệ";
      case "faq" -> "Câu hỏi thường gặp";
      case "booking" -> "Sơ đồ và đặt bàn";
      case "lookup" -> "Tra cứu đặt bàn";
      case "confirmation" -> "Xác nhận đặt bàn";
      case "login" -> "Đăng nhập và đăng ký";
      case "account" -> "Thông tin cá nhân";
      case "history" -> "Lịch sử đặt bàn";
      case "orders" -> "Đơn hàng";
      case "vouchers" -> "Ví voucher";
      case "addresses" -> "Địa chỉ";
      case "invoices" -> "Hóa đơn";
      case "preorder" -> "Đặt món trước";
      case "TABLE_MAP_VIEW" -> "Xem sơ đồ bàn";
      case "VOUCHER_VIEW" -> "Xem ví voucher";
      case "VOUCHER_QUOTE" -> "Kiểm tra mã voucher thành công";
      case "VOUCHER_APPLY" -> "Áp dụng voucher vào đặt món";
      case "CART_ADD" -> "Thêm món";
      case "CART_REMOVE" -> "Xóa món";
      case "CART_QUANTITY" -> "Đổi số lượng";
      case "CHAT_OPEN" -> "Mở ChatBox";
      case "CHAT_MESSAGE" -> "Lượt gửi tin thành công";
      default -> value;
    };
  }

  private Map<String,?> params(Filter filter) {
    return Map.of("start",filter.from().atStartOfDay(),"end",filter.to().plusDays(1).atStartOfDay(),"actor",filter.actor());
  }

  public void export(Report report, PrintWriter writer) {
    writer.print('\ufeff');
    row(writer,"Báo cáo","Chỉ số","Số lượng","Giá trị bổ sung");
    row(writer,"Bộ lọc","Từ ngày",report.filter().from(),report.filter().actor());
    row(writer,"Bộ lọc","Đến ngày",report.filter().to(),report.filter().grain());
    var t = report.summary();
    row(writer,"Tổng quan","Lượt truy cập",t.views(),"");
    row(writer,"Tổng quan","Khách duy nhất",t.visitors(),"");
    row(writer,"Tổng quan","Phiên",t.sessions(),"");
    row(writer,"Tổng quan","Hoạt động trung bình (giây)",t.activeAverage(),"");
    row(writer,"Tổng quan","Chuyển đổi (%)",t.conversion(),"");
    row(writer,"Tổng quan","Bỏ dở (%)",t.abandonment(),t.abandoned());
    metrics(writer,"Theo thời gian",report.series());
    metrics(writer,"Đối tượng",report.audiences());
    metrics(writer,"Trang (lượt, giây)",report.pages());
    metrics(writer,"Món (xem, nhấn)",report.dishes());
    metrics(writer,"Từ khóa",report.searches());
    metrics(writer,"Danh mục",report.categories());
    metrics(writer,"Giờ",report.hours());
    metrics(writer,"ChatBox",report.chat());
    metrics(writer,"Giỏ món",report.cart());
    metrics(writer,"Sơ đồ bàn và thanh toán",report.behaviors());
    report.funnel().forEach(step -> row(writer,"Phễu",step.label(),step.count(),step.rate()));
    report.branches().forEach(branch -> branch.steps().forEach(step -> row(writer,branch.label(),step.label(),step.count(),step.rate())));
  }

  private void metrics(PrintWriter writer, String group, List<Metric> metrics) {
    metrics.forEach(metric -> row(writer,group,metric.label(),metric.count(),metric.secondary()));
  }
  private void row(PrintWriter writer, Object... cells) {
    writer.println(Arrays.stream(cells).map(Object::toString).map(s -> {
      if (!s.isBlank() && "=+-@\t\r\n".indexOf(s.stripLeading().isEmpty() ? ' ' : s.stripLeading().charAt(0)) >= 0) s = "'" + s;
      return "\"" + s.replace("\"","\"\"") + "\"";
    }).collect(java.util.stream.Collectors.joining(",")));
  }
}
