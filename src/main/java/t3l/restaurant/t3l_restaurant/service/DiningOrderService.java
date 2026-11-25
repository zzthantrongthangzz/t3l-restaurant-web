package t3l.restaurant.t3l_restaurant.service;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.entity.DiningOrder;
import t3l.restaurant.t3l_restaurant.entity.DiningOrderItem;
import t3l.restaurant.t3l_restaurant.entity.DiningPayment;
import t3l.restaurant.t3l_restaurant.entity.Dish;
import t3l.restaurant.t3l_restaurant.entity.PaymentIntent;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.repository.DiningOrderRepository;
import t3l.restaurant.t3l_restaurant.repository.DiningPaymentRepository;
import t3l.restaurant.t3l_restaurant.repository.DishRepository;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;
import t3l.restaurant.t3l_restaurant.repository.RestaurantTableRepository;
import t3l.restaurant.t3l_restaurant.repository.PaymentIntentRepository;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
public class DiningOrderService {
  private final ReservationRepository reservations;
  private final RestaurantTableRepository tables;
  private final ReservationService bookingService;
  private final DiningOrderRepository orders;
  private final DiningPaymentRepository payments;
  private final DishRepository dishes;
  private final StaffShiftService shifts;
  private final ReservationSchedule schedule;
  private final PaymentIntentRepository paymentIntents;
  private final ReservationEmailQueue emails;
  private final PaymentReferences references;
  private final PaymentIntentService qrPayments;
  private final VoucherService vouchers;

  public record OrderView(Reservation booking, DiningOrder order, DiningPayment payment) {
    public BigDecimal getTotal() {
      return order == null ? BigDecimal.ZERO : order.getTotalAmount() == null ? order.getNetTotal() : order.getTotalAmount();
    }
    public BigDecimal getDepositApplied() { return payment == null ? booking.getAvailableDeposit().min(getTotal()) : payment.getDepositApplied(); }
    public BigDecimal getBalance() { return payment == null ? getTotal().subtract(getDepositApplied()) : BigDecimal.ZERO; }
    public BigDecimal getExcess() {
      return payment == null ? booking.getAvailableDeposit().subtract(getDepositApplied()) : booking.getRefundRemaining();
    }
  }
  public record ServingTable(Reservation booking, DiningOrder order) {}

  @Transactional(readOnly = true)
  public List<ServingTable> servingTables() {
    var serving = reservations.findByStatusInAndTableIsNotNull(List.of(Reservation.SEATED));
    if (serving.isEmpty()) return List.of();
    var openOrders = orders.findByReservation_IdIn(serving.stream().map(Reservation::getId).toList()).stream()
        .collect(Collectors.toMap(order -> order.getReservation().getId(), Function.identity()));
    return serving.stream().sorted(Comparator.comparing(Reservation::getStartsAt))
        .map(booking -> new ServingTable(booking, openOrders.get(booking.getId()))).toList();
  }

  @Transactional(readOnly = true)
  public OrderView view(Long reservationId) {
    Reservation booking = reservations.findOneById(reservationId).orElse(null);
    if (booking == null) return null;
    DiningOrder order = orders.findByReservation_Id(reservationId).orElse(null);
    DiningPayment payment = order == null ? null : payments.findByOrder_Id(order.getId()).orElse(null);
    return new OrderView(booking, order, payment);
  }

  @PreAuthorize("hasAnyRole('STAFF', 'ADMIN') and #actor == authentication.name")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void open(Long reservationId, String actor) {
    Reservation booking = lockBooking(reservationId);
    requireSeated(booking);
    if (orders.findByReservation_Id(reservationId).isEmpty()) {
      orders.save(new DiningOrder(booking, actor, now()));
    }
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void addItem(Long reservationId, Long dishId, int quantity) {
    DiningOrder order = draft(reservationId);
    validateQuantity(quantity);
    Dish dish = dishes.findSellable(dishId)
        .orElseThrow(() -> new BookingException(null, "Món không còn được bán."));
    order.addItem(dish, quantity);
    vouchers.reprice(order,order.getCurrentTotal());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean adjustItem(Long reservationId, Long itemId, Integer quantity, boolean cancel) {
    DiningOrder order = draft(reservationId);
    DiningOrderItem item = order.getItems().stream().filter(line -> line.getId().equals(itemId)).findFirst()
        .orElseThrow(() -> new BookingException(null, "Không tìm thấy món trong hóa đơn này."));
    if (cancel) item.cancel();
    else {
      if (item.isCancelled()) throw new BookingException(null, "Món này đã bị hủy.");
      validateQuantity(quantity);
      item.changeQuantity(quantity);
    }
    return vouchers.reprice(order,order.getCurrentTotal());
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public void voucher(Long reservationId, String code, String operation) {
    Reservation booking=lockBooking(reservationId);
    requireSeated(booking);
    DiningOrder order=requireOrder(reservationId);
    if (!List.of(DiningOrder.DRAFT,DiningOrder.FINALIZED).contains(order.getStatus()) || payments.findByOrder_Id(order.getId()).isPresent())
      throw new BookingException("voucherCode","Hóa đơn đã đóng hoặc đã thanh toán.");
    if (!List.of("apply","replace","remove").contains(operation))
      throw new BookingException("voucherCode","Thao tác voucher không hợp lệ.");
    if ("apply".equals(operation) && order.getVoucherCode()!=null)
      throw new BookingException("voucherCode","Hóa đơn đã giữ một voucher. Chọn thay mã hoặc gỡ mã hiện tại.");
    if (!"remove".equals(operation) && (code==null || code.isBlank()))
      throw new BookingException("voucherCode","Hãy nhập mã voucher.");
    vouchers.guardInvoice(order);
    vouchers.change(order,booking,"remove".equals(operation) ? "" : code,order.getCurrentTotal());
    if (DiningOrder.FINALIZED.equals(order.getStatus())) order.finalizeOrder(now());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void finalizeOrder(Long reservationId) {
    Reservation booking = lockBooking(reservationId);
    requireSeated(booking);
    DiningOrder order = requireOrder(reservationId);
    if (DiningOrder.FINALIZED.equals(order.getStatus()) || DiningOrder.PAID.equals(order.getStatus())) return;
    if (!DiningOrder.DRAFT.equals(order.getStatus()) || !order.hasItems()) {
      throw new BookingException(null, "Thêm ít nhất một món trước khi chốt hóa đơn.");
    }
    if (order.getCurrentTotal().precision() > 16) throw new BookingException(null, "Tổng hóa đơn vượt giới hạn.");
    order.finalizeOrder(now());
  }

  @PreAuthorize("hasAnyRole('STAFF', 'ADMIN') and #actor == authentication.name")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void collect(Long reservationId, String method, String reference, boolean received, String actor) {
    if (!received) throw new BookingException(null, "Hãy xác nhận đã thực nhận tiền trước khi ghi thanh toán.");
    boolean transfer = "TRANSFER".equals(method);
    if (transfer && qrPayments.usesQr()) {
      throw new BookingException(null, "Chuyển khoản phải được SePay xác minh qua mã QR trước khi xác nhận.");
    }
    if (!transfer) requireManualPaymentMethod(method);
    String transferReference = transfer ? references.normalize(reference, 1) : null;
    tables.lockInventory();
    Reservation booking = lockBooking(reservationId);
    DiningOrder order = requireOrder(reservationId);
    if (DiningOrder.PAID.equals(order.getStatus())) {
      DiningPayment previous = payments.findByOrder_Id(order.getId()).orElseThrow();
      if (method.equals(previous.getPaymentMethod()) && Objects.equals(transferReference, previous.getTransferReference())) return;
      throw new BookingException(null, "Hóa đơn đã được thanh toán.");
    }
    requireSeated(booking);
    if (!DiningOrder.FINALIZED.equals(order.getStatus())) throw new BookingException(null, "Hãy chốt hóa đơn trước khi thu tiền.");
    var applied = booking.getAvailableDeposit().min(order.getTotalAmount());
    boolean covered = applied.compareTo(order.getTotalAmount()) == 0;
    if (covered != "DEPOSIT".equals(method)) {
      throw new BookingException(null, covered ? "Hóa đơn đã được cọc chi trả. Hãy chốt bằng tiền cọc."
          : "Hóa đơn còn số tiền cần thu.");
    }
    var shift = shifts.requireOpenShiftForUpdate(actor);
    if (transfer) references.claim(transferReference, "INVOICE", order.getId());
    var paidAt = now();
    payments.saveAndFlush(new DiningPayment(order, shift, method, transferReference, applied, actor, paidAt));
    booking.settleDeposit(applied, paidAt);
    if (applied.signum() > 0) markDepositIntentApplied(reservationId);
    vouchers.consume(order,booking.isDepositDemo());
    order.markPaid();
    bookingService.finishService(booking, actor, now());
    if (applied.signum() > 0) emails.enqueue(booking,
        t3l.restaurant.t3l_restaurant.entity.ReservationEmail.Kind.DEPOSIT_APPLIED, null,
        "Đã khấu trừ " + applied.toPlainString() + " đ, thu thêm " + order.getTotalAmount().subtract(applied).toPlainString() + " đ.");
  }

  @PreAuthorize("hasAnyRole('STAFF', 'ADMIN') and #actor == authentication.name")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void collectVerifiedTransfer(Long reservationId, String paymentCode, String actor) {
    if (!qrPayments.usesQr() || paymentCode == null || paymentCode.isBlank()) {
      throw new BookingException(null, "Hãy chọn mã thanh toán đã được xác minh.");
    }
    tables.lockInventory();
    Reservation booking = lockBooking(reservationId);
    DiningOrder order = requireOrder(reservationId);
    if (DiningOrder.PAID.equals(order.getStatus())) throw new BookingException(null, "Hóa đơn đã được thanh toán.");
    requireSeated(booking);
    if (!DiningOrder.FINALIZED.equals(order.getStatus())) throw new BookingException(null, "Hãy chốt hóa đơn trước khi thu tiền.");
    PaymentIntent intent = paymentIntents.findByPaymentCode(paymentCode)
        .flatMap(found -> paymentIntents.lockById(found.getId()))
        .orElseThrow(() -> new BookingException(null, "Không tìm thấy mã thanh toán cho hóa đơn này."));
    if (!PaymentIntent.INVOICE.equals(intent.getPaymentType()) || !PaymentIntent.RECEIVED.equals(intent.getStatus())
        || !Objects.equals(intent.getReservationId(), reservationId) || !Objects.equals(intent.getOrderId(), order.getId())) {
      throw new BookingException(null, "SePay chưa xác minh giao dịch cho hóa đơn này.");
    }
    BigDecimal applied = booking.getAvailableDeposit().min(order.getTotalAmount());
    BigDecimal balance = order.getTotalAmount().subtract(applied);
    if (balance.signum() <= 0 || balance.compareTo(intent.getAmount()) != 0) {
      throw new BookingException(null, "Số tiền cần thanh toán đã thay đổi. Hãy tạo lại mã QR.");
    }
    var shift = shifts.requireOpenShiftForUpdate(actor);
    String reference = intent.getProviderTransactionId();
    if (reference == null || payments.existsByTransferReference(reference)) {
      throw new BookingException(null, "Giao dịch này đã được ghi nhận cho một hóa đơn khác.");
    }
    payments.saveAndFlush(new DiningPayment(order, shift, "TRANSFER", reference, applied, actor, now(), intent.isDemo()));
    booking.settleDeposit(applied, now());
    if (applied.signum() > 0) markDepositIntentApplied(reservationId);
    order.markPaid();
    bookingService.finishService(booking, actor, now());
    vouchers.consume(order,intent.isDemo() || booking.isDepositDemo());
    intent.markApplied(now());
    if (applied.signum() > 0) emails.enqueue(booking,
        t3l.restaurant.t3l_restaurant.entity.ReservationEmail.Kind.DEPOSIT_APPLIED, null,
        "Đã khấu trừ " + applied.toPlainString() + " đ, thu thêm " + balance.toPlainString() + " đ."
            + (intent.isDemo() ? " Khoản thanh toán hóa đơn này ở chế độ DEMO." : ""));
  }

  private void requireManualPaymentMethod(String method) {
    if ("CASH".equals(method) || "CARD".equals(method) || "DEPOSIT".equals(method)) return;
    throw new BookingException(null, "Phương thức thanh toán không hợp lệ.");
  }

  private void markDepositIntentApplied(Long reservationId) {
    LocalDateTime appliedAt = now();
    paymentIntents.findFirstByPaymentTypeAndReservationIdOrderByIdDesc(PaymentIntent.DEPOSIT, reservationId)
        .filter(payment -> PaymentIntent.RECEIVED.equals(payment.getStatus()))
        .ifPresent(payment -> payment.markApplied(appliedAt));
  }

  private DiningOrder draft(Long reservationId) {
    requireSeated(lockBooking(reservationId));
    DiningOrder order = requireOrder(reservationId);
    if (!DiningOrder.DRAFT.equals(order.getStatus())) throw new BookingException(null, "Hóa đơn đã chốt và không thể sửa món.");
    return order;
  }

  private DiningOrder requireOrder(Long reservationId) {
    return orders.findByReservation_Id(reservationId)
        .orElseThrow(() -> new BookingException(null, "Chưa mở hóa đơn cho lượt phục vụ này."));
  }

  private Reservation lockBooking(Long reservationId) {
    // All order writers acquire the reservation row first, as do table moves and completion.
    return reservations.lockById(reservationId).orElseThrow(() -> new BookingException(null, "Không tìm thấy lượt đặt bàn."));
  }

  private void requireSeated(Reservation booking) {
    if (!Reservation.SEATED.equals(booking.getStatus())) throw new BookingException(null, "Chỉ gọi món hoặc thu tiền cho lượt đang phục vụ.");
  }

  private void validateQuantity(Integer quantity) {
    if (quantity == null || quantity < 1 || quantity > 100) throw new BookingException(null, "Số lượng phải từ 1 đến 100.");
  }

  private LocalDateTime now() { return LocalDateTime.now(schedule.getClock()); }
}
