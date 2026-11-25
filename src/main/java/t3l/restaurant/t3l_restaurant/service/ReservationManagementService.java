package t3l.restaurant.t3l_restaurant.service;

import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.dto.*;
import t3l.restaurant.t3l_restaurant.entity.*;
import t3l.restaurant.t3l_restaurant.entity.ReservationEmail.Kind;
import t3l.restaurant.t3l_restaurant.repository.*;

@Service
public class ReservationManagementService {
  private final ReservationRepository reservations;
  private final ReservationChangeRequestRepository requests;
  private final RestaurantTableRepository tables;
  private final ReservationService bookings;
  private final ReservationSchedule schedule;
  private final ReservationChangePolicy policy;
  private final Validator validator;
  private final ReservationEmailQueue emails;
  private final AdminAudit audit;
  private final DepositPolicy deposits;
  private final PreorderService preorders;
  private final VoucherService vouchers;

  public ReservationManagementService(ReservationRepository reservations, ReservationChangeRequestRepository requests,
      RestaurantTableRepository tables, ReservationService bookings, ReservationSchedule schedule,
      ReservationChangePolicy policy, Validator validator, ReservationEmailQueue emails, AdminAudit audit,
      DepositPolicy deposits, PreorderService preorders, VoucherService vouchers) {
    this.reservations = reservations;
    this.requests = requests;
    this.tables = tables;
    this.bookings = bookings;
    this.schedule = schedule;
    this.policy = policy;
    this.validator = validator;
    this.emails = emails;
    this.audit = audit;
    this.deposits = deposits;
    this.preorders = preorders;
    this.vouchers = vouchers;
  }

  static String canonicalPhone(String value) {
    String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
    return digits.startsWith("84") && digits.length() == 11 ? "0" + digits.substring(2) : digits;
  }

  @Transactional(readOnly = true)
  public Reservation lookup(ReservationLookupForm form) {
    validate(form);
    Reservation booking = reservations.findByCode(form.getCode()).orElse(null);
    String expected = booking == null ? "" : canonicalPhone(booking.getGuestPhone());
    boolean matches = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
        canonicalPhone(form.getPhone()).getBytes(StandardCharsets.UTF_8));
    return booking != null && booking.getCustomerId() == null && matches ? booking : null;
  }

  @Transactional(readOnly = true)
  public List<ReservationChangeRequest> history(Long id) {
    return requests.findTop20ByReservation_IdOrderByCreatedAtDescIdDesc(id);
  }

  @Transactional(readOnly = true)
  public String requestBlockedReason(Reservation booking) {
    if (booking.hasExpiredDeposit(policy.now())) return "Lượt đặt đã quá hạn cọc.";
    String reason = policy.blockedReason(booking);
    return reason != null ? reason : requests.existsByReservation_IdAndStatus(booking.getId(), "PENDING")
        ? "Bạn đã có một yêu cầu đang chờ nhân viên duyệt. Lịch hiện tại được giữ nguyên đến khi có kết quả." : null;
  }

  @Transactional(readOnly = true)
  public String preorderBlockedReason(Reservation booking) {
    String reason = requestBlockedReason(booking);
    if (reason == null && "TRANSFER_SUBMITTED".equals(booking.getDepositStatus())) {
      return "Vui lòng đợi đối soát tiền cọc trước khi đổi món.";
    }
    return reason;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ReservationChangeRequest requestPreorder(String code, Long customerId, PreorderForm form) {
    validate(form);
    tables.lockInventory();
    var booking = lockBooking(code);
    if (customerId == null) throw new BookingException(null, "Vui lòng đăng nhập để đổi món.");
    requireOwner(booking, customerId);
    if (booking.getTable() == null) throw new BookingException(null, "Lượt đặt chưa có bàn được phân.");
    requireNewRequest(booking);
    bookings.requireDepositChangeAllowed(booking);
    var order = preorders.order(booking.getId());
    var quote = preorders.quote(form, order);
    if (quote.voucherCode()!=null && !quote.voucherCode().isBlank())
      vouchers.preview(quote.voucherCode(),quote.total(),customerId,booking,order);
    if (quote.equals(preorders.current(order))) throw new BookingException(null, "Giỏ món và ghi chú chưa thay đổi.");
    return requests.saveAndFlush(ReservationChangeRequest.preorder(booking, preorders.encode(quote), policy.now()));
  }

  private <T> void validate(T form) {
    validator.validate(form).stream().findFirst().ifPresent(violation -> {
      throw new BookingException(violation.getPropertyPath().toString(), violation.getMessage());
    });
  }

  private Reservation lockBooking(String code) {
    Long id = reservations.findIdByCode(code)
        .orElseThrow(() -> new BookingException(null, "Không thể xử lý yêu cầu đặt bàn này."));
    return reservations.lockById(id).orElseThrow(() -> new BookingException(null, "Không tìm thấy đặt bàn."));
  }

  private void requireNewRequest(Reservation booking) {
    if (booking.hasExpiredDeposit(policy.now())) throw new BookingException(null, "Lượt đặt đã quá hạn cọc.");
    policy.requireEligible(booking);
    if (requests.existsByReservation_IdAndStatus(booking.getId(), "PENDING")) {
      throw new BookingException(null, "Đã có yêu cầu đang chờ duyệt cho đặt bàn này.");
    }
  }

  private void requireOwner(Reservation booking, Long customerId) {
    if (!Objects.equals(booking.getCustomerId(), customerId)) {
      throw new BookingException(null, "Bạn không có quyền xử lý đặt bàn này.");
    }
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Reservation claim(String code, UserAccount customer) {
    Reservation booking = lockBooking(code);
    requireOwner(booking, null);
    booking.assignCustomer(customer);
    return booking;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ReservationChangeRequest requestChange(String code, ReservationUpdateForm form) {
    return requestChange(code, form, null);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ReservationChangeRequest requestChange(String code, ReservationUpdateForm form, Long customerId) {
    validate(form);
    var active = tables.lockActiveTables();
    Reservation booking = lockBooking(code);
    requireOwner(booking, customerId);
    requireNewRequest(booking);
    var end = schedule.validateAndGetEnd(form.scheduleForm());
    var start = form.getDate().atTime(form.getTime());
    policy.requireProposedNotice(start);
    String area = form.getPreferredArea() == null ? "" : form.getPreferredArea().trim();
    if (booking.getReservationDate().equals(form.getDate()) && booking.getReservationTime().equals(form.getTime())
        && booking.getGuestCount() == form.getGuests() && Objects.equals(normalizeArea(booking.getPreferredArea()), area)) {
      throw new BookingException(null, "Thông tin mới chưa có thay đổi so với lịch hiện tại.");
    }
    bookings.selectTable(active, start, end, form.getGuests(), area, booking.getId());
    return requests.saveAndFlush(new ReservationChangeRequest(booking, "CHANGE", form, form.getReason(), policy.now()));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ReservationChangeRequest requestCancellation(String code, ReservationCancellationForm form) {
    return requestCancellation(code, form, null);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ReservationChangeRequest requestCancellation(String code, ReservationCancellationForm form, Long customerId) {
    validate(form);
    tables.lockActiveTables();
    Reservation booking = lockBooking(code);
    requireOwner(booking, customerId);
    requireNewRequest(booking);
    return requests.saveAndFlush(new ReservationChangeRequest(booking, "CANCEL", null, form.getReason(), policy.now()));
  }

  private String normalizeArea(String area) { return area == null ? "" : area.trim(); }

  public record ReviewItem(ReservationChangeRequest request, String blockedReason, PreorderService.Quote preorder) {}

  @Transactional(readOnly = true)
  public List<ReviewItem> pendingReviews() {
    return requests.findByStatusOrderByCreatedAtAscIdAsc("PENDING").stream().map(request -> {
      String reason = policy.blockedReason(request.getReservation());
      var preorder = preorders.decode(request.getPreorderPayload());
      if (preorder != null && reason == null) {
        try {
          bookings.requireDepositChangeAllowed(request.getReservation());
          verifyPreorderQuote(request.getReservation(), preorder, preorders.order(request.getReservation().getId()));
        } catch (BookingException exception) {
          reason = exception.getMessage();
        }
      }
      if (reason == null && "CHANGE".equals(request.getRequestType())
          && request.getRequestedDate().atTime(request.getRequestedTime()).isBefore(policy.now().plusMinutes(policy.noticeMinutes()))) {
        reason = "Giờ mới không còn đủ thời gian báo trước " + policy.noticeMinutes() + " phút.";
      }
      return new ReviewItem(request, reason, preorder);
    }).toList();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void review(Long requestId, String decision, String note, String reviewer) {
    if (!List.of("APPROVE", "REJECT").contains(decision)) throw new BookingException(null, "Thao tác duyệt không hợp lệ.");
    if (note != null && note.length() > 500) throw new BookingException(null, "Phản hồi không vượt quá 500 ký tự.");
    if ("REJECT".equals(decision) && (note == null || note.isBlank())) throw new BookingException(null, "Vui lòng nhập lý do từ chối để thông báo cho khách.");
    var active = tables.lockActiveTables();
    Long bookingId = requests.findReservationId(requestId)
        .orElseThrow(() -> new BookingException(null, "Không tìm thấy yêu cầu."));
    Reservation booking = reservations.lockById(bookingId).orElseThrow();
    ReservationChangeRequest request = requests.lockById(requestId).orElseThrow();
    if (!"PENDING".equals(request.getStatus())) throw new BookingException(null, "Yêu cầu đã được xử lý trước đó.");
    boolean approve = "APPROVE".equals(decision);
    boolean cancellation = "CANCEL".equals(request.getRequestType());
    boolean preorder = "PREORDER".equals(request.getRequestType());
    if (approve) {
      if (booking.hasExpiredDeposit(policy.now())) throw new BookingException(null, "Lượt đặt đã quá hạn cọc.");
      policy.requireEligible(booking);
      if (!booking.getReservationDate().equals(request.getOriginalDate())
          || !booking.getReservationTime().equals(request.getOriginalTime())
          || booking.getGuestCount() != request.getOriginalGuests()
          || !normalizeArea(booking.getPreferredArea()).equals(normalizeArea(request.getOriginalArea()))) {
        throw new BookingException(null, "Lịch gốc đã thay đổi. Vui lòng từ chối yêu cầu cũ và liên hệ khách.");
      }
      if (cancellation) {
        booking.updateStatus("CANCELLED");
        booking.closeDeposit(deposits.cancellationPercent(booking, request.getCreatedAt()), false, policy.now());
        preorders.close(booking.getId());
      } else if (preorder) {
        bookings.requireDepositChangeAllowed(booking);
        var quote = preorders.decode(request.getPreorderPayload());
        var order = preorders.order(booking.getId());
        verifyPreorderQuote(booking, quote, order);
        preorders.apply(booking, order, quote, policy.now());
        bookings.repriceDeposit(booking);
      } else {
        bookings.requireDepositChangeAllowed(booking);
        var form = request.updateForm();
        validate(form);
        var end = schedule.validateAndGetEnd(form.scheduleForm());
        var start = form.getDate().atTime(form.getTime());
        policy.requireProposedNotice(start);
        var table = bookings.selectTable(active, start, end, form.getGuests(), normalizeArea(form.getPreferredArea()), bookingId);
        booking.reschedule(table, form.getDate(), form.getTime(), end, form.getGuests(), form.getPreferredArea());
        bookings.repriceDeposit(booking);
      }
    }
    request.review(approve, reviewer, note, policy.now());
    reservations.flush();
    requests.flush();
    Kind kind;
    if (preorder) kind = approve ? Kind.PREORDER_CHANGED : Kind.PREORDER_DECLINED;
    else if (approve) kind = cancellation ? Kind.CANCELLATION_APPROVED : Kind.RESCHEDULED;
    else kind = cancellation ? Kind.CANCELLATION_DECLINED : Kind.CHANGE_DECLINED;
    emails.enqueue(booking, kind, requestId, request.getReviewNote());
    audit.record("Xét duyệt yêu cầu", booking.getCode() + " · " + request.getTypeLabel()
        + (approve ? " · Đã duyệt" : " · Đã từ chối"));
  }

  private void verifyPreorderQuote(Reservation booking, PreorderService.Quote requested, DiningOrder order) {
    if (booking.getCustomerId() == null) throw new BookingException(null, "Yêu cầu đổi món thiếu tài khoản đặt bàn.");
    if (booking.getTable() == null) throw new BookingException(null, "Lượt đặt chưa có bàn được phân.");
    if (requested.voucherCode()!=null && !requested.voucherCode().isBlank())
      vouchers.preview(requested.voucherCode(),requested.total(),booking.getCustomerId(),booking,order);
    var current = preorders.quote(requested.quantities(), requested.note(), order);
    if (!requested.lines().equals(current.lines()) || !Objects.equals(requested.note(),current.note())) {
      throw new BookingException(null, "Giá hoặc thông tin món đã thay đổi. Vui lòng từ chối và yêu cầu khách xác nhận lại.");
    }
  }
}
