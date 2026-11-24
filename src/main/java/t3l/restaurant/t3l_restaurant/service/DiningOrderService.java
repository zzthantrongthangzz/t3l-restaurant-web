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
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.repository.DiningOrderRepository;
import t3l.restaurant.t3l_restaurant.repository.DiningPaymentRepository;
import t3l.restaurant.t3l_restaurant.repository.DishRepository;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
public class DiningOrderService {
  private final ReservationRepository reservations;
  private final DiningOrderRepository orders;
  private final DiningPaymentRepository payments;
  private final DishRepository dishes;
  private final StaffShiftService shifts;
  private final ReservationSchedule schedule;
  private final PaymentReferences references;
  private final ReservationEmailQueue emails;

  public record OrderView(Reservation booking, DiningOrder order, DiningPayment payment) {
    public BigDecimal getTotal() {
      return order == null ? BigDecimal.ZERO : order.getTotalAmount() == null ? order.getCurrentTotal() : order.getTotalAmount();
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
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void adjustItem(Long reservationId, Long itemId, Integer quantity, boolean cancel) {
    DiningOrder order = draft(reservationId);
    DiningOrderItem item = order.getItems().stream().filter(line -> line.getId().equals(itemId)).findFirst()
        .orElseThrow(() -> new BookingException(null, "Không tìm thấy món trong hóa đơn này."));
    if (cancel) item.cancel();
    else {
      if (item.isCancelled()) throw new BookingException(null, "Món này đã bị hủy.");
      validateQuantity(quantity);
      item.changeQuantity(quantity);
    }
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
    String transferReference = paymentReference(method, reference);
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
    if (transferReference != null && payments.existsByTransferReference(transferReference)) {
      throw new BookingException(null, "Mã giao dịch đã được dùng cho một hóa đơn khác.");
    }
    if (transferReference != null) references.claim(transferReference, "INVOICE", order.getId());
    payments.saveAndFlush(new DiningPayment(order, shift, method, transferReference, applied, actor, now()));
    booking.settleDeposit(applied, now());
    order.markPaid();
    if (applied.signum() > 0) emails.enqueue(booking,
        t3l.restaurant.t3l_restaurant.entity.ReservationEmail.Kind.DEPOSIT_APPLIED, null,
        "Đã khấu trừ " + applied.toPlainString() + " đ, thu thêm " + order.getTotalAmount().subtract(applied).toPlainString() + " đ.");
  }

  private String paymentReference(String method, String value) {
    if ("CASH".equals(method) || "DEPOSIT".equals(method)) return null;
    if (!"TRANSFER".equals(method)) throw new BookingException(null, "Phương thức thanh toán không hợp lệ.");
    return references.normalize(value, 1);
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
