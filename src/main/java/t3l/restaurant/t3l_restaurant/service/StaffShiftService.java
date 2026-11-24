package t3l.restaurant.t3l_restaurant.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.dto.CashSaleForm;
import t3l.restaurant.t3l_restaurant.entity.CashSale;
import t3l.restaurant.t3l_restaurant.entity.DiningPayment;
import t3l.restaurant.t3l_restaurant.entity.Dish;
import t3l.restaurant.t3l_restaurant.entity.StaffShift;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.CashSaleRepository;
import t3l.restaurant.t3l_restaurant.repository.DiningPaymentRepository;
import t3l.restaurant.t3l_restaurant.repository.DishRepository;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;
import t3l.restaurant.t3l_restaurant.repository.StaffShiftRepository;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('STAFF', 'ADMIN') and #username == authentication.name")
public class StaffShiftService {
  private final StaffShiftRepository shifts;
  private final CashSaleRepository sales;
  private final UserAccountRepository accounts;
  private final DishRepository dishes;
  private final DiningPaymentRepository payments;
  private final ReservationRepository reservations;
  private final ReservationSchedule schedule;

  public record Totals(long paidOrders, long directSales, long guests, BigDecimal cash,
                       BigDecimal transfer, BigDecimal card, BigDecimal expectedCash) {
    public BigDecimal getRevenue() { return cash.add(transfer).add(card); }
  }

  public record ShiftView(StaffShift current, StaffShift selected, Totals totals,
                          List<CashSale> sales, List<DiningPayment> payments, List<StaffShift> history) {}

  @PreAuthorize("hasAnyRole('STAFF', 'ADMIN') and #username == authentication.name and (!#admin or hasRole('ADMIN'))")
  @Transactional(readOnly = true)
  public ShiftView overview(String username, Long selectedId, boolean admin) {
    var current = current(username);
    var history = admin ? shifts.findTop20ByOrderByOpenedAtDesc()
        : shifts.findTop20ByEmployee_UsernameOrderByOpenedAtDesc(username);
    StaffShift selected = selectedId == null ? current : shifts.findOneById(selectedId)
        .filter(shift -> admin || shift.getEmployee().getUsername().equals(username)).orElse(null);
    if (selected == null) return new ShiftView(current, null, null, List.of(), List.of(), history);
    var directSales = sales.findByShift_IdOrderByCreatedAtDesc(selected.getId());
    var received = payments.findByShift_IdOrderByPaidAtDescIdDesc(selected.getId());
    return new ShiftView(current, selected, totals(selected, directSales, received), directSales, received, history);
  }

  @Transactional(readOnly = true)
  public StaffShift current(String username) {
    return shifts.findFirstByEmployee_UsernameAndStatusOrderByOpenedAtDesc(username, "OPEN").orElse(null);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public StaffShift open(String username, BigDecimal openingCash) {
    BigDecimal cash = validateMoney(openingCash, 14, "Tiền đầu ca");
    UserAccount employee = lockEmployee(username);
    if (current(username) != null) throw new IllegalStateException("Bạn đang có một ca làm chưa kết thúc.");
    return shifts.save(new StaffShift(employee, cash, LocalDateTime.now(schedule.getClock())));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public CashSale addSale(String username, CashSaleForm form) {
    StaffShift shift = requireOpenShiftForUpdate(username);
    Dish dish = dishes.findSellable(form.getDishId())
        .orElseThrow(() -> new IllegalArgumentException("Món không còn được bán."));
    if (form.getQuantity() < 1 || form.getQuantity() > 100) {
      throw new IllegalArgumentException("Số lượng phải từ 1 đến 100.");
    }
    String method = form.getPaymentMethod();
    if (!"CASH".equals(method) && !"TRANSFER".equals(method) && !"CARD".equals(method)) {
      throw new IllegalArgumentException("Phương thức thanh toán không hợp lệ.");
    }
    return sales.save(new CashSale(shift, dish, form.getQuantity(), method, LocalDateTime.now(schedule.getClock())));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public StaffShift close(String username, BigDecimal countedCash) {
    BigDecimal cash = validateMoney(countedCash, 18, "Tiền thực đếm");
    StaffShift shift = requireOpenShiftForUpdate(username);
    Totals totals = totals(shift, sales.findByShift_IdOrderByCreatedAtDesc(shift.getId()),
        payments.findByShift_IdOrderByPaidAtDescIdDesc(shift.getId()));
    shift.close(totals.cash(), totals.transfer().add(totals.card()), cash, LocalDateTime.now(schedule.getClock()));
    return shift;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public StaffShift requireOpenShiftForUpdate(String username) {
    // Serialize opens, collections and reconciliation for this employee, including when no shift row exists.
    lockEmployee(username);
    StaffShift shift = current(username);
    if (shift == null) throw new IllegalStateException("Hãy mở ca làm việc trước khi nhận bàn hoặc ghi nhận doanh thu.");
    return shift;
  }

  private UserAccount lockEmployee(String username) {
    return accounts.findByUsername(username).flatMap(employee -> accounts.lockById(employee.getId()))
        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản nhân viên."));
  }

  private Totals totals(StaffShift shift, List<CashSale> directSales, List<DiningPayment> received) {
    var realPayments = received.stream().filter(payment -> !payment.isDemo()).toList();
    BigDecimal cash = totalFor("CASH", directSales, realPayments);
    return new Totals(realPayments.size(), directSales.size(), reservations.guestsServedByShift(shift.getId()), cash,
        totalFor("TRANSFER", directSales, realPayments), totalFor("CARD", directSales, realPayments), shift.getOpeningCash().add(cash));
  }

  private BigDecimal totalFor(String method, List<CashSale> directSales, List<DiningPayment> received) {
    return directSales.stream().filter(sale -> method.equals(sale.getPaymentMethod())).map(CashSale::getTotalAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .add(received.stream().filter(payment -> !payment.isDemo() && method.equals(payment.getPaymentMethod()))
            .map(DiningPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  private BigDecimal validateMoney(BigDecimal value, int digits, String label) {
    if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > 0
        || value.compareTo(BigDecimal.TEN.pow(digits)) >= 0) {
      throw new IllegalArgumentException(label + " phải là số nguyên đồng Việt Nam hợp lệ.");
    }
    return value.setScale(0, RoundingMode.UNNECESSARY);
  }
}
