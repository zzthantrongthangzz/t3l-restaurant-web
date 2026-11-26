package t3l.restaurant.t3l_restaurant.controller;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import t3l.restaurant.t3l_restaurant.dto.PreorderForm;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;
import t3l.restaurant.t3l_restaurant.repository.RestaurantTableRepository;
import t3l.restaurant.t3l_restaurant.service.*;

@RestController
@RequiredArgsConstructor
public class VoucherQuoteController {
  private final CustomerAccountService customers;
  private final ReservationService bookings;
  private final PreorderService preorders;
  private final VoucherService vouchers;
  private final RestaurantTableRepository tables;
  private final DepositPolicy deposits;

  @PostMapping("/account/vouchers/quote")
  @PreAuthorize("hasRole('BUYER')")
  public Quote quote(@Valid @ModelAttribute PreorderForm form,@RequestParam(required=false) Long tableId,
      @RequestParam(required=false) String bookingCode,Authentication authentication) {
    var buyer=customers.signedInCustomer(authentication);
    Reservation booking=bookingCode==null || bookingCode.isBlank() ? null : bookings.findVisibleByCode(bookingCode,buyer,false);
    if (bookingCode!=null && !bookingCode.isBlank() && (booking==null || !bookings.owns(booking,buyer) || !booking.isOpenBooking()))
      throw new BookingException("voucherCode","Lượt đặt bàn không còn đủ điều kiện báo giá.");
    var table=booking==null ? tables.findById(tableId==null ? -1L : tableId).orElse(null) : booking.getTable();
    requireActive(table);
    var order=booking==null ? null : preorders.order(booking.getId());
    var gross=preorders.quote(form,order).total();
    var discount=vouchers.preview(form.getVoucherCode(),gross,buyer.getId(),booking,order);
    var deposit=deposits.required(table,gross.subtract(discount));
    if (booking!=null && (discount.signum()>0 || vouchers.affected(order))) vouchers.guardDeposit(booking,deposit);
    return totals(gross,discount,deposit,booking==null ? BigDecimal.ZERO : booking.getDepositPaid());
  }

  @PostMapping("/reservations/quote")
  public Quote quoteReservation(@Valid @ModelAttribute PreorderForm form,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time,
      @RequestParam Integer guests, Authentication authentication) {
    var gross = preorders.quote(form, null).total();
    var discount = BigDecimal.ZERO;
    if (form.getVoucherCode() != null && !form.getVoucherCode().isBlank()) {
      var customer = customers.signedInCustomer(authentication);
      if (customer == null) throw new BookingException("voucherCode", "Vui lòng đăng nhập để sử dụng voucher.");
      discount = vouchers.preview(form.getVoucherCode(), gross, customer.getId(), null, null);
    }
    var visit = new ReservationForm();
    visit.setDate(date);
    visit.setTime(time);
    visit.setGuests(guests);
    return totals(gross, discount, bookings.estimateWebsiteDeposit(visit, gross.subtract(discount)), BigDecimal.ZERO);
  }

  private void requireActive(RestaurantTable table) {
    if (table==null || !table.isActive()) throw new BookingException("voucherCode","Hãy chọn bàn đang phục vụ.");
  }

  private Quote totals(BigDecimal gross, BigDecimal discount, BigDecimal deposit, BigDecimal paid) {
    var net = gross.subtract(discount);
    return new Quote(gross.toPlainString(),discount.toPlainString(),net.toPlainString(),deposit.toPlainString(),
        deposit.subtract(paid).max(BigDecimal.ZERO).toPlainString(),
        net.subtract(paid.max(deposit)).max(BigDecimal.ZERO).toPlainString());
  }

  public record Quote(String gross,String discount,String net,String deposit,String topup,String balance) {}

  @ExceptionHandler(BookingException.class)
  public ResponseEntity<?> invalid(BookingException error) {
    return ResponseEntity.badRequest().body(Map.of("message",error.getMessage()));
  }
}
