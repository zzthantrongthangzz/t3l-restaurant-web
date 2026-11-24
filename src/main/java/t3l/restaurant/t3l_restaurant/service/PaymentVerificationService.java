package t3l.restaurant.t3l_restaurant.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.entity.DiningOrder;
import t3l.restaurant.t3l_restaurant.entity.PaymentIntent;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.repository.DiningOrderRepository;
import t3l.restaurant.t3l_restaurant.repository.PaymentIntentRepository;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;

@Service
@RequiredArgsConstructor
public class PaymentVerificationService {
  private final PaymentIntentRepository intents;
  private final ReservationRepository reservations;
  private final DiningOrderRepository orders;
  private final ReservationService bookings;
  private final SepayProperties properties;
  private final ReservationSchedule schedule;

  public record Incoming(String id, String reference, String account, String direction, BigDecimal amountIn,
                         BigDecimal amountOut, String content, LocalDateTime transactionAt) {}

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void process(Long intentId, Incoming incoming, boolean demo) {
    Long reservationId = intents.findReservationId(intentId)
        .orElseThrow(() -> new BookingException(null, "Không tìm thấy yêu cầu thanh toán."));
    reservations.lockById(reservationId);
    PaymentIntent intent = intents.lockById(intentId)
        .orElseThrow(() -> new BookingException(null, "Không tìm thấy yêu cầu thanh toán."));
    if (PaymentIntent.RECEIVED.equals(intent.getStatus()) || PaymentIntent.APPLIED.equals(intent.getStatus())
        || PaymentIntent.REVIEW_REQUIRED.equals(intent.getStatus())) return;
    if (!PaymentIntent.PENDING.equals(intent.getStatus()) && !PaymentIntent.EXPIRED.equals(intent.getStatus())
        && !PaymentIntent.CLOSED.equals(intent.getStatus())) return;

    String reason = validationReason(intent, incoming, demo);
    if (reason == null && PaymentIntent.CLOSED.equals(intent.getStatus())) {
      reason = "Mã thanh toán đã đóng; cần đối soát giao dịch đến muộn";
    }
    if (reason == null && incoming.id() != null && intents.existsByProviderTransactionId(incoming.id())) {
      reason = "ID giao dịch SePay đã được dùng";
    }
    if (reason == null && PaymentIntent.DEPOSIT.equals(intent.getPaymentType())) {
      boolean applied = bookings.receiveVerifiedDeposit(intent.getReservationId(), intent.getAmount(), incoming.reference(),
          incoming.transactionAt(), demo);
      if (!applied) reason = "Đặt bàn đã đóng, đổi trạng thái hoặc không còn chờ đủ tiền cọc";
    }
    if (reason == null && PaymentIntent.INVOICE.equals(intent.getPaymentType()) && !invoiceStillPayable(intent)) {
      reason = "Hóa đơn đã thay đổi trạng thái hoặc số tiền cần thanh toán";
    }

    LocalDateTime now = now();
    if (reason != null) {
      boolean transactionAlreadyUsed = incoming.id() != null && intents.existsByProviderTransactionId(incoming.id());
      intent.requireReview(transactionAlreadyUsed ? null : incoming.id(), incoming.reference(), incoming.account(),
          incoming.content(), incoming.transactionAt(), transactionAlreadyUsed ? "ID giao dịch SePay đã được dùng" : reason, demo);
      return;
    }
    String transactionId = demo ? "DEMO-" + intent.getId() + "-" + UUID.randomUUID() : incoming.id();
    intent.markReceived(transactionId, incoming.reference(), incoming.account(), incoming.content(),
        incoming.transactionAt(), now, demo);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PaymentIntent simulate(String paymentCode) {
    if (!properties.demoAllowed()) throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.NOT_FOUND);
    PaymentIntent intent = intents.findByPaymentCode(paymentCode)
        .orElseThrow(() -> new BookingException(null, "Không tìm thấy yêu cầu thanh toán."));
    var now = now();
    String account = properties.bankAccount().isBlank() ? "DEMO-ACCOUNT" : properties.bankAccount();
    String direction = "in";
    var incoming = new Incoming("DEMO-" + intent.getId(), "DEMO-" + intent.getPaymentCode(), account,
        direction, intent.getAmount(), BigDecimal.ZERO, "DEMO " + intent.getPaymentCode(), now);
    process(intent.getId(), incoming, true);
    return intents.findByPaymentCode(paymentCode).orElseThrow();
  }

  private String validationReason(PaymentIntent intent, Incoming transaction, boolean demo) {
    if (transaction == null || transaction.id() == null || transaction.transactionAt() == null) return "Thiếu dữ liệu giao dịch";
    String content = normalize(transaction.content());
    String code = normalize(intent.getPaymentCode());
    if (!content.contains(code)) return "Nội dung chuyển khoản không khớp mã thanh toán";
    if (!demo && (properties.bankAccount().isBlank()
        || !digits(transaction.account()).equals(digits(properties.bankAccount())))) return "Giao dịch vào sai tài khoản nhận";
    if (!"in".equalsIgnoreCase(transaction.direction()) || transaction.amountIn() == null
        || transaction.amountIn().signum() <= 0 || transaction.amountOut() == null || transaction.amountOut().signum() != 0) {
      return "Giao dịch không phải khoản tiền vào";
    }
    if (transaction.amountIn().compareTo(intent.getAmount()) != 0) return "Số tiền chuyển không khớp yêu cầu";
    if (transaction.transactionAt().isBefore(intent.getCreatedAt().minusMinutes(2))) return "Giao dịch phát sinh trước khi tạo QR";
    if (transaction.transactionAt().isAfter(intent.getExpiresAt())) return "Giao dịch đến sau hạn thanh toán";
    return null;
  }

  private boolean invoiceStillPayable(PaymentIntent intent) {
    if (intent.getOrderId() == null || intent.getReservationId() == null) return false;
    DiningOrder order = orders.findById(intent.getOrderId()).orElse(null);
    Reservation booking = reservations.findOneById(intent.getReservationId()).orElse(null);
    if (order == null || booking == null || !DiningOrder.FINALIZED.equals(order.getStatus())
        || !Reservation.SEATED.equals(booking.getStatus()) || !order.getReservation().getId().equals(booking.getId())) return false;
    BigDecimal due = order.getTotalAmount().subtract(booking.getAvailableDeposit().min(order.getTotalAmount()));
    return due.compareTo(intent.getAmount()) == 0;
  }

  private String normalize(String value) {
    return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
  }

  private String digits(String value) { return value == null ? "" : value.replaceAll("\\D", ""); }

  private LocalDateTime now() { return LocalDateTime.now(schedule.getClock()); }
}
