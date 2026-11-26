package t3l.restaurant.t3l_restaurant.service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import t3l.restaurant.t3l_restaurant.entity.DiningOrder;
import t3l.restaurant.t3l_restaurant.entity.PaymentIntent;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.repository.DiningOrderRepository;
import t3l.restaurant.t3l_restaurant.repository.PaymentIntentRepository;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;

@Service
@RequiredArgsConstructor
public class PaymentIntentService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final List<String> OPEN_STATUSES = List.of(PaymentIntent.PENDING, PaymentIntent.RECEIVED, PaymentIntent.REVIEW_REQUIRED);

  private final PaymentIntentRepository intents;
  private final ReservationRepository reservations;
  private final DiningOrderRepository orders;
  private final ReservationSchedule schedule;
  private final SepayProperties properties;

  public record Checkout(PaymentIntent intent, String qrUrl, String bankName, String bankAccount, String accountHolder) {
    public boolean hasQr() { return qrUrl != null; }
  }
  public record PaymentStatus(String status, String label, boolean demo, boolean confirmEnabled, long remainingSeconds,
                               boolean receiveEnabled, String receiveReason) {
    public PaymentStatus(String status, String label, boolean demo, boolean confirmEnabled, long remainingSeconds) {
      this(status, label, demo, confirmEnabled, remainingSeconds, false, "");
    }
  }
  public record CustomerDepositStatus(String status, String label, long remainingSeconds, boolean payable,
                                      String reservationStatus) {}
  public record CustomerDeposit(CustomerDepositStatus status, Checkout checkout) {}

  public boolean usesQr() { return properties.enabled() || properties.demoAllowed(); }

  @Transactional(readOnly = true)
  public CustomerDeposit customerDeposit(Reservation booking) {
    var intent = usesQr() && booking.isOpenBooking() && booking.isDepositOutstanding()
        ? intents.findFirstByPaymentTypeAndReservationIdOrderByIdDesc(PaymentIntent.DEPOSIT, booking.getId()).orElse(null)
        : null;
    var status = customerDepositStatus(booking, intent, now());
    return new CustomerDeposit(status, status.payable() ? checkout(intent) : null);
  }

  @Transactional(readOnly = true)
  public Map<Long, CustomerDepositStatus> customerDepositStatuses(List<Reservation> bookings) {
    var candidateIds = bookings.stream().filter(booking -> booking.isOpenBooking() && booking.isDepositOutstanding())
        .map(Reservation::getId).toList();
    Map<Long, PaymentIntent> current = !usesQr() || candidateIds.isEmpty() ? Map.of()
        : intents.latestDeposits(candidateIds).stream().collect(Collectors.toMap(PaymentIntent::getReservationId, Function.identity()));
    var now = now();
    return bookings.stream().collect(Collectors.toMap(Reservation::getId,
        booking -> customerDepositStatus(booking, current.get(booking.getId()), now)));
  }

  private CustomerDepositStatus customerDepositStatus(Reservation booking, PaymentIntent intent, LocalDateTime now) {
    String state = booking.getDepositStatus();
    String label = booking.getDepositStatusLabel();
    long seconds = 0;
    boolean payable = waitingForCustomerDeposit(booking, now) && (!usesQr() || activeCustomerQr(booking, intent, now));
    if (payable) {
      var deadline = booking.getDepositDueAt();
      if (intent != null && intent.getExpiresAt().isBefore(deadline)) deadline = intent.getExpiresAt();
      seconds = Math.max(1, (Duration.between(now, deadline).toMillis() + 999) / 1000);
      state = "WAITING";
    } else if (booking.hasExpiredDeposit(now)) {
      state = "EXPIRED";
      label = "Đã hết hạn thanh toán cọc";
    } else if (booking.isOpenBooking() && intent != null && PaymentIntent.REVIEW_REQUIRED.equals(intent.getStatus())) {
      state = "REVIEW_REQUIRED";
      label = "Giao dịch cần nhà hàng đối soát";
    } else if ("WAITING_PAYMENT".equals(state)) {
      state = "UNAVAILABLE";
      label = "Chưa có hướng dẫn thanh toán khả dụng. Vui lòng liên hệ T3L.";
    }
    return new CustomerDepositStatus(state, label, seconds, payable, booking.getStatus());
  }

  private boolean waitingForCustomerDeposit(Reservation booking, LocalDateTime now) {
    return booking.isOpenBooking() && booking.isDepositOutstanding() && "WAITING_PAYMENT".equals(booking.getDepositStatus())
        && booking.getDepositDueAt() != null && booking.getDepositDueAt().isAfter(now)
        && (usesQr() || Reservation.CONFIRMED.equals(booking.getStatus()));
  }

  private boolean activeCustomerQr(Reservation booking, PaymentIntent intent, LocalDateTime now) {
    return properties.paymentReady() && intent != null && PaymentIntent.PENDING.equals(intent.getStatus())
        && intent.getExpiresAt().isAfter(now) && intent.getAmount().compareTo(booking.getDepositRemaining()) == 0;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PaymentIntent ensureDepositIntent(Reservation booking) {
    if (!usesQr() || booking == null) return null;
    var current = intents.findFirstByPaymentTypeAndReservationIdAndStatusInOrderByIdDesc(
        PaymentIntent.DEPOSIT, booking.getId(), OPEN_STATUSES);
    if (booking.getDepositAmount() == null || booking.getDepositAmount().signum() <= 0
        || booking.getDepositRemaining().signum() <= 0) {
      current.filter(intent -> PaymentIntent.PENDING.equals(intent.getStatus())).ifPresent(PaymentIntent::close);
      return null;
    }
    if (current.isPresent() && PaymentIntent.PENDING.equals(current.get().getStatus())
        && current.get().getAmount().compareTo(booking.getDepositRemaining()) == 0) {
      return current.get();
    }
    current.filter(intent -> PaymentIntent.REVIEW_REQUIRED.equals(intent.getStatus())).ifPresent(intent -> {
      throw new BookingException(null, "Giao dịch cọc cần được nhà hàng đối soát trước khi tạo mã mới.");
    });
    current.filter(intent -> PaymentIntent.PENDING.equals(intent.getStatus())).ifPresent(PaymentIntent::close);
    LocalDateTime now = now();
    LocalDateTime expiresAt = booking.getDepositDueAt() == null ? now.plusMinutes(120) : booking.getDepositDueAt();
    return create(PaymentIntent.DEPOSIT, booking.getId(), null, booking.getDepositRemaining(), now, expiresAt);
  }

  @PreAuthorize("hasAnyRole('STAFF', 'ADMIN') and #actor == authentication.name")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PaymentIntent ensureInvoiceIntent(Long reservationId, String actor) {
    Reservation booking = reservations.lockById(reservationId)
        .orElseThrow(() -> new BookingException(null, "Không tìm thấy lượt đặt bàn."));
    DiningOrder order = orders.findByReservation_Id(reservationId)
        .orElseThrow(() -> new BookingException(null, "Chưa mở hóa đơn cho lượt phục vụ này."));
    if (!Reservation.SEATED.equals(booking.getStatus()) || !DiningOrder.FINALIZED.equals(order.getStatus())) {
      throw new BookingException(null, "Chỉ tạo QR cho hóa đơn đã chốt của bàn đang phục vụ.");
    }
    BigDecimal applied = booking.getAvailableDeposit().min(order.getTotalAmount());
    BigDecimal balance = order.getTotalAmount().subtract(applied);
    if (balance.signum() <= 0) throw new BookingException(null, "Hóa đơn đã được tiền cọc thanh toán đủ.");

    var current = intents.findFirstByPaymentTypeAndOrderIdAndStatusInOrderByIdDesc(
        PaymentIntent.INVOICE, order.getId(), OPEN_STATUSES);
    if (current.filter(intent -> PaymentIntent.PENDING.equals(intent.getStatus()) && now().isAfter(intent.getExpiresAt())).isPresent()) {
      current.ifPresent(PaymentIntent::close);
      current = java.util.Optional.empty();
    }
    if (current.isPresent() && current.get().getAmount().compareTo(balance) == 0) return current.get();
    if (current.filter(intent -> PaymentIntent.RECEIVED.equals(intent.getStatus())).isPresent()) {
      throw new BookingException(null, "Hóa đơn đã nhận giao dịch cần được nhân viên xác nhận trước.");
    }
    if (current.filter(intent -> PaymentIntent.REVIEW_REQUIRED.equals(intent.getStatus())).isPresent()) {
      throw new BookingException(null, "Giao dịch cần được nhà hàng đối soát trước khi tạo mã QR mới.");
    }
    current.ifPresent(PaymentIntent::close);
    LocalDateTime now = now();
    return create(PaymentIntent.INVOICE, reservationId, order.getId(), balance, now,
        now.plusMinutes(properties.invoicePaymentMinutes()));
  }

  @Transactional(readOnly = true)
  public Checkout depositCheckout(Long reservationId) {
    return intents.findFirstByPaymentTypeAndReservationIdOrderByIdDesc(PaymentIntent.DEPOSIT, reservationId)
        .map(this::checkout).orElse(null);
  }

  @Transactional(readOnly = true)
  public PaymentStatus depositStatus(Reservation booking) {
    return depositStatus(booking, false);
  }

  @Transactional(readOnly = true)
  public PaymentStatus depositStatus(Reservation booking, boolean shiftOpen) {
    LocalDateTime now = now();
    PaymentIntent intent = intents.findFirstByPaymentTypeAndReservationIdOrderByIdDesc(PaymentIntent.DEPOSIT, booking.getId())
        .orElse(null);
    boolean canConfirm = Reservation.PENDING.equals(booking.getStatus())
        && (!usesQr() || !booking.isDepositOutstanding());
    if (intent != null && PaymentIntent.REVIEW_REQUIRED.equals(intent.getStatus())) {
      return withReceiveAction(booking, shiftOpen, new PaymentStatus("REVIEW_REQUIRED", "Giao dịch cần nhà hàng đối soát", intent.isDemo(), false, 0), now);
    }
    if (Reservation.CANCELLED.equals(booking.getStatus()) || Reservation.NO_SHOW.equals(booking.getStatus())) {
      return withReceiveAction(booking, shiftOpen, new PaymentStatus(booking.getStatus(), booking.getStatusLabel(), intent != null && intent.isDemo(), false, 0), now);
    }
    if (booking.getDepositAmount().signum() > 0 && "PAID".equals(booking.getDepositStatus())) {
      if (!Reservation.PENDING.equals(booking.getStatus())) {
        return withReceiveAction(booking, shiftOpen, new PaymentStatus("CONFIRMED", "Đặt bàn đã được nhà hàng xác nhận", intent != null && intent.isDemo(), false, 0), now);
      }
      return withReceiveAction(booking, shiftOpen, new PaymentStatus("RECEIVED", "Đã nhận tiền cọc – chờ nhà hàng xác nhận", intent != null && intent.isDemo(), canConfirm, 0), now);
    }
    String depositState = booking.getDepositStatus();
    if (List.of("APPLIED", "REFUND_PENDING", "REFUNDED", "FORFEITED", "CLOSED", "EXPIRED")
        .contains(depositState)) {
      boolean demo = booking.isDepositDemo() || (intent != null && intent.isDemo());
      return withReceiveAction(booking, shiftOpen,
          new PaymentStatus(depositState, booking.getDepositStatusLabel(), demo, false, 0), now);
    }
    boolean unpaidActiveBooking = booking.isOpenBooking() && booking.isDepositOutstanding();
    if (unpaidActiveBooking && (booking.hasExpiredDeposit(now)
        || (intent != null && now.isAfter(intent.getExpiresAt())))) {
      return withReceiveAction(booking, shiftOpen, new PaymentStatus("EXPIRED", "Đã hết hạn thanh toán cọc", intent != null && intent.isDemo(), false, 0), now);
    }
    if (booking.getDepositAmount().signum() <= 0) {
      if (!Reservation.PENDING.equals(booking.getStatus())) {
        return withReceiveAction(booking, shiftOpen, new PaymentStatus("CONFIRMED", "Đặt bàn đã được nhà hàng xác nhận", false, false, 0), now);
      }
      return withReceiveAction(booking, shiftOpen, new PaymentStatus("NO_DEPOSIT", "Không yêu cầu đặt cọc – chờ nhà hàng xác nhận", false, canConfirm, 0), now);
    }
    long remaining = booking.getDepositDueAt() == null ? 0 : Math.max(0, Duration.between(now, booking.getDepositDueAt()).getSeconds());
    return withReceiveAction(booking, shiftOpen, new PaymentStatus("WAITING", "Chờ thanh toán cọc", false, false, remaining), now);
  }

  private PaymentStatus withReceiveAction(Reservation booking, boolean shiftOpen, PaymentStatus status, LocalDateTime now) {
    boolean confirmed = Reservation.CONFIRMED.equals(booking.getStatus()) || Reservation.CHECKED_IN.equals(booking.getStatus());
    boolean sameDay = booking.getReservationDate().equals(now.toLocalDate());
    boolean serviceHours = !now.toLocalTime().isBefore(schedule.getOpeningTime()) && now.toLocalTime().isBefore(schedule.getClosingTime());
    boolean arrivalWindow = !now.isBefore(booking.getStartsAt().minusMinutes(schedule.getCheckInMinutes()))
        && now.isBefore(booking.getStartsAt().plusMinutes(schedule.getNoShowMinutes()));
    boolean enabled = confirmed && sameDay && serviceHours && arrivalWindow && shiftOpen && !booking.isDepositOutstanding();
    String reason = !confirmed ? "Đặt bàn chưa được xác nhận."
        : !shiftOpen ? "Cần mở ca làm trước khi nhận khách."
        : booking.isDepositOutstanding() ? "Chưa xác minh đủ tiền cọc."
        : !sameDay ? "Chỉ nhận khách trong ngày đặt bàn."
        : !serviceHours ? "Ngoài giờ phục vụ."
        : !arrivalWindow ? "Chưa đến khung giờ nhận khách hoặc đã quá thời gian chờ." : "";
    return new PaymentStatus(status.status(), status.label(), status.demo(), status.confirmEnabled(), status.remainingSeconds(), enabled, reason);
  }

  @Transactional(readOnly = true)
  public PaymentStatus intentStatus(PaymentIntent intent) {
    if (intent == null) return new PaymentStatus("WAITING", "Chưa tạo mã thanh toán", false, false, 0);
    if (PaymentIntent.REVIEW_REQUIRED.equals(intent.getStatus())) {
      return new PaymentStatus("REVIEW_REQUIRED", "Giao dịch cần nhà hàng đối soát", intent.isDemo(), false, 0);
    }
    if (PaymentIntent.APPLIED.equals(intent.getStatus())) {
      return new PaymentStatus("APPLIED", "Đã ghi nhận thanh toán", intent.isDemo(), false, 0);
    }
    if (PaymentIntent.RECEIVED.equals(intent.getStatus())) {
      return new PaymentStatus("RECEIVED", "Đã xác minh đủ tiền – chờ nhân viên xác nhận", intent.isDemo(), true, 0);
    }
    if (LocalDateTime.now(schedule.getClock()).isAfter(intent.getExpiresAt())) {
      return new PaymentStatus("EXPIRED", "Đã hết hạn thanh toán", intent.isDemo(), false, 0);
    }
    long remaining = Math.max(0, Duration.between(LocalDateTime.now(schedule.getClock()), intent.getExpiresAt()).getSeconds());
    return new PaymentStatus("WAITING", "Đang chờ thanh toán", intent.isDemo(), false, remaining);
  }

  @Transactional(readOnly = true)
  public Checkout invoiceCheckout(Long reservationId) {
    DiningOrder order = orders.findByReservation_Id(reservationId).orElse(null);
    if (order == null) return null;
    return intents.findFirstByPaymentTypeAndOrderIdOrderByIdDesc(PaymentIntent.INVOICE, order.getId())
        .map(this::checkout).orElse(null);
  }

  @Transactional(readOnly = true)
  public PaymentIntent findByCode(String paymentCode) {
    return intents.findByPaymentCode(paymentCode).orElse(null);
  }

  public Checkout checkout(PaymentIntent intent) {
    if (intent == null) return null;
    String qr = null;
    if (properties.paymentReady()) {
      qr = UriComponentsBuilder.fromUriString("https://vietqr.app/img")
          .queryParam("acc", properties.bankAccount())
          .queryParam("bank", properties.bankName())
          .queryParam("amount", intent.getAmount().toBigInteger().toString())
          .queryParam("des", intent.getPaymentCode())
          .queryParam("template", "compact")
          .queryParam("showinfo", "true")
          .queryParam("fullacc", "true")
          .queryParam("holder", properties.accountHolder())
          .build().encode().toUriString();
    }
    return new Checkout(intent, qr, properties.bankName(), properties.bankAccount(), properties.accountHolder());
  }

  PaymentIntent create(String paymentType, Long reservationId, Long orderId, BigDecimal amount,
      LocalDateTime createdAt, LocalDateTime expiresAt) {
    if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0 || amount.precision() > 16) {
      throw new BookingException(null, "Số tiền thanh toán không hợp lệ.");
    }
    amount = amount.setScale(0, java.math.RoundingMode.UNNECESSARY);
    for (int attempt = 0; attempt < 8; attempt++) {
      String code = paymentType.equals(PaymentIntent.DEPOSIT) ? "TCOC" + randomDigits() : "THD" + randomDigits();
      if (!intents.existsByPaymentCode(code)) {
        return intents.saveAndFlush(new PaymentIntent(code, paymentType, reservationId, orderId, amount, createdAt, expiresAt));
      }
    }
    throw new BookingException(null, "Chưa tạo được mã thanh toán. Vui lòng thử lại.");
  }

  private String randomDigits() { return "%08d".formatted(RANDOM.nextInt(100_000_000)); }
  private LocalDateTime now() { return LocalDateTime.now(schedule.getClock()); }
}
