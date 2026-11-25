package t3l.restaurant.t3l_restaurant.controller;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import t3l.restaurant.t3l_restaurant.dto.PreorderForm;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.repository.RestaurantTableRepository;
import t3l.restaurant.t3l_restaurant.service.*;

@RestController
@RequestMapping("/account/vouchers/quote")
@RequiredArgsConstructor
@PreAuthorize("hasRole('BUYER')")
public class VoucherQuoteController {
  private final CustomerAccountService customers;
  private final ReservationService bookings;
  private final PreorderService preorders;
  private final VoucherService vouchers;
  private final RestaurantTableRepository tables;
  private final DepositPolicy deposits;

  @PostMapping
  public Quote quote(@Valid @ModelAttribute PreorderForm form,@RequestParam(required=false) Long tableId,
      @RequestParam(required=false) String bookingCode,Authentication authentication) {
    var buyer=customers.signedInCustomer(authentication);
    Reservation booking=bookingCode==null || bookingCode.isBlank() ? null : bookings.findVisibleByCode(bookingCode,buyer,false);
    if (bookingCode!=null && !bookingCode.isBlank() && (booking==null || !bookings.owns(booking,buyer) || !booking.isOpenBooking()))
      throw new BookingException("voucherCode","Lượt đặt bàn không còn đủ điều kiện báo giá.");
    var table=booking==null ? tables.findById(tableId==null ? -1L : tableId).orElse(null) : booking.getTable();
    if (table==null || !table.isActive()) throw new BookingException("voucherCode","Hãy chọn bàn đang phục vụ.");
    var order=booking==null ? null : preorders.order(booking.getId());
    var gross=preorders.quote(form,order).total();
    var discount=vouchers.preview(form.getVoucherCode(),gross,buyer.getId(),booking,order);
    var net=gross.subtract(discount);
    var deposit=deposits.required(table,net);
    var paid=booking==null ? BigDecimal.ZERO : booking.getDepositPaid();
    if (booking!=null && (discount.signum()>0 || vouchers.affected(order))) vouchers.guardDeposit(booking,deposit);
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
