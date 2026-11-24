package t3l.restaurant.t3l_restaurant.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;

@Component
public class DepositPolicy {
  private final int paymentMinutes;
  private final int reviewMinutes;
  private final int fullRefundNoticeMinutes;
  private final int lateRefundPercent;
  private final int noShowRefundPercent;

  public DepositPolicy(@Value("${app.deposits.payment-minutes:120}") int paymentMinutes,
      @Value("${app.deposits.review-minutes:120}") int reviewMinutes,
      @Value("${app.deposits.full-refund-notice-minutes:1440}") int fullRefundNoticeMinutes,
      @Value("${app.deposits.late-refund-percent:50}") int lateRefundPercent,
      @Value("${app.deposits.no-show-refund-percent:0}") int noShowRefundPercent) {
    if (paymentMinutes < 1 || reviewMinutes < 1 || fullRefundNoticeMinutes < 0
        || lateRefundPercent < 0 || lateRefundPercent > 100 || noShowRefundPercent < 0 || noShowRefundPercent > 100) {
      throw new IllegalArgumentException("Cấu hình thời hạn hoặc tỷ lệ hoàn cọc không hợp lệ.");
    }
    this.paymentMinutes = paymentMinutes;
    this.reviewMinutes = reviewMinutes;
    this.fullRefundNoticeMinutes = fullRefundNoticeMinutes;
    this.lateRefundPercent = lateRefundPercent;
    this.noShowRefundPercent = noShowRefundPercent;
  }

  public BigDecimal minimum(RestaurantTable table) {
    String area = table.getTableType() == null ? "" : table.getTableType().toLowerCase(Locale.ROOT);
    if (area.contains("vip")) return BigDecimal.valueOf(500_000);
    return area.contains("lớn") || table.getCapacity() >= 9 ? BigDecimal.valueOf(300_000) : BigDecimal.ZERO;
  }

  public BigDecimal required(RestaurantTable table, BigDecimal preorderTotal) {
    return minimum(table).max(preorderTotal.multiply(new BigDecimal("0.70")).setScale(0, RoundingMode.CEILING));
  }

  public LocalDateTime paymentDeadline(Reservation booking, LocalDateTime now) {
    return earlier(now.plusMinutes(paymentMinutes), booking.getStartsAt());
  }

  public LocalDateTime reviewDeadline(Reservation booking, LocalDateTime now) {
    return earlier(now.plusMinutes(reviewMinutes), booking.getStartsAt());
  }

  private LocalDateTime earlier(LocalDateTime a, LocalDateTime b) { return a.isBefore(b) ? a : b; }

  public int cancellationPercent(Reservation booking, LocalDateTime requestedAt) {
    return requestedAt.isAfter(booking.getStartsAt().minusMinutes(fullRefundNoticeMinutes)) ? lateRefundPercent : 100;
  }

  public int noShowPercent() { return noShowRefundPercent; }

  public String refundTerms() {
    return "Hủy trước ít nhất " + fullRefundNoticeMinutes + " phút: hoàn 100%; hủy muộn: hoàn "
        + lateRefundPercent + "%; không đến: hoàn " + noShowRefundPercent
        + "%. Nhà hàng hủy hoặc không kịp xác nhận thanh toán: hoàn 100% tiền đã nhận.";
  }
}
