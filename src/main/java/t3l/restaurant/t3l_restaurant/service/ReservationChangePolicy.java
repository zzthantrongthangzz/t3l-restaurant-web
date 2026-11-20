package t3l.restaurant.t3l_restaurant.service;

import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import t3l.restaurant.t3l_restaurant.entity.Reservation;

@Component
public class ReservationChangePolicy {
  private final ReservationSchedule schedule;

  public ReservationChangePolicy(ReservationSchedule schedule) { this.schedule = schedule; }

  public LocalDateTime now() { return LocalDateTime.now(schedule.getClock()); }
  public LocalDateTime deadline(Reservation booking) { return booking.getStartsAt().minusMinutes(noticeMinutes()); }
  public int noticeMinutes() { return schedule.getChangeNoticeMinutes(); }

  public String blockedReason(Reservation booking) {
    if (!"PENDING_CONFIRMATION".equals(booking.getStatus()) && !"CONFIRMED".equals(booking.getStatus())) {
      return "Đặt bàn ở trạng thái “" + booking.getStatusLabel() + "” không thể hủy hoặc thay đổi.";
    }
    if (!booking.getStartsAt().isAfter(now())) return "Đặt bàn đã quá giờ đến và hết hiệu lực thay đổi.";
    if (now().isAfter(deadline(booking))) return "Chỉ nhận yêu cầu hủy hoặc thay đổi trước giờ đặt tối thiểu " + noticeMinutes() + " phút.";
    return null;
  }

  public void requireEligible(Reservation booking) {
    String reason = blockedReason(booking);
    if (reason != null) throw new BookingException(null, reason);
  }

  public void requireProposedNotice(LocalDateTime start) {
    if (start.isBefore(now().plusMinutes(noticeMinutes()))) {
      throw new BookingException("time", "Giờ mới phải cách thời điểm hiện tại tối thiểu " + noticeMinutes() + " phút.");
    }
  }
}
