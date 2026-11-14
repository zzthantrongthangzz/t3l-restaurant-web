package t3l.restaurant.t3l_restaurant.service;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.dto.CashSaleForm;
import t3l.restaurant.t3l_restaurant.entity.CashSale;
import t3l.restaurant.t3l_restaurant.entity.Dish;
import t3l.restaurant.t3l_restaurant.entity.StaffShift;
import t3l.restaurant.t3l_restaurant.entity.UserAccount;
import t3l.restaurant.t3l_restaurant.repository.CashSaleRepository;
import t3l.restaurant.t3l_restaurant.repository.DishRepository;
import t3l.restaurant.t3l_restaurant.repository.StaffShiftRepository;
import t3l.restaurant.t3l_restaurant.repository.UserAccountRepository;

@Service
@PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
public class StaffShiftService {
  private final StaffShiftRepository shifts;
  private final CashSaleRepository sales;
  private final UserAccountRepository accounts;
  private final DishRepository dishes;

  public StaffShiftService(
      StaffShiftRepository shifts,
      CashSaleRepository sales,
      UserAccountRepository accounts,
      DishRepository dishes) {
    this.shifts = shifts;
    this.sales = sales;
    this.accounts = accounts;
    this.dishes = dishes;
  }

  @Transactional(readOnly = true)
  public StaffShift current(String username) {
    return shifts.findFirstByEmployee_UsernameAndStatusOrderByOpenedAtDesc(username, "OPEN").orElse(null);
  }

  @Transactional(readOnly = true)
  public List<CashSale> salesFor(StaffShift shift) {
    return shift == null ? List.of() : sales.findByShift_IdOrderByCreatedAtDesc(shift.getId());
  }

  @Transactional(readOnly = true)
  public List<StaffShift> recentShifts(String username, boolean admin) {
    return admin
        ? shifts.findTop20ByOrderByOpenedAtDesc()
        : shifts.findTop20ByEmployee_UsernameOrderByOpenedAtDesc(username);
  }

  @Transactional
  public StaffShift open(String username, BigDecimal openingCash) {
    if (openingCash == null || openingCash.signum() < 0) {
      throw new IllegalArgumentException("Tiền đầu ca không được âm.");
    }
    if (current(username) != null) {
      throw new IllegalStateException("Bạn đang có một ca làm chưa kết thúc.");
    }
    UserAccount employee = accounts.findByUsername(username).orElseThrow();
    return shifts.save(new StaffShift(employee, openingCash));
  }

  @Transactional
  public CashSale addSale(String username, CashSaleForm form) {
    StaffShift shift = requireOpenShift(username);
    Dish dish =
        dishes
            .findById(form.getDishId())
            .filter(Dish::isAvailable)
            .orElseThrow(() -> new IllegalArgumentException("Món không còn được bán."));
    if (form.getQuantity() < 1 || form.getQuantity() > 100) {
      throw new IllegalArgumentException("Số lượng phải từ 1 đến 100.");
    }
    String method = form.getPaymentMethod();
    if (!"CASH".equals(method) && !"TRANSFER".equals(method) && !"CARD".equals(method)) {
      throw new IllegalArgumentException("Phương thức thanh toán không hợp lệ.");
    }
    return sales.save(new CashSale(shift, dish, form.getQuantity(), method));
  }

  @Transactional
  public StaffShift close(String username, BigDecimal countedCash) {
    StaffShift shift = requireOpenShift(username);
    if (countedCash == null || countedCash.signum() < 0) {
      throw new IllegalArgumentException("Tiền thực đếm không được âm.");
    }
    BigDecimal cash = sales.totalByShiftAndMethod(shift.getId(), "CASH");
    BigDecimal other =
        sales
            .totalByShiftAndMethod(shift.getId(), "TRANSFER")
            .add(sales.totalByShiftAndMethod(shift.getId(), "CARD"));
    shift.close(cash, other, countedCash);
    return shift;
  }

  private StaffShift requireOpenShift(String username) {
    StaffShift shift = current(username);
    if (shift == null) {
      throw new IllegalStateException("Hãy mở ca trước khi ghi nhận doanh thu.");
    }
    return shift;
  }
}
