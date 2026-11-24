package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import t3l.restaurant.t3l_restaurant.dto.ReservationUpdateForm;

@Entity
@Table(name = "reservation_change_requests")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ReservationChangeRequest {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "reservation_id", nullable = false)
  private Reservation reservation;
  @Column(name = "request_type", nullable = false, length = 16)
  private String requestType;
  @Column(nullable = false, length = 16)
  private String status = "PENDING";
  @Column(name = "original_date", nullable = false)
  private LocalDate originalDate;
  @Column(name = "original_time", nullable = false)
  private LocalTime originalTime;
  @Column(name = "original_guests", nullable = false)
  private int originalGuests;
  @Column(name = "original_area", length = 40)
  private String originalArea;
  @Column(name = "requested_date")
  private LocalDate requestedDate;
  @Column(name = "requested_time")
  private LocalTime requestedTime;
  @Column(name = "requested_guests")
  private Integer requestedGuests;
  @Column(name = "requested_area", length = 40)
  private String requestedArea;
  @Column(length = 500)
  private String reason;
  @Column(name = "preorder_payload", columnDefinition = "TEXT")
  private String preorderPayload;
  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;
  @Column(name = "reviewed_at")
  private LocalDateTime reviewedAt;
  @Column(name = "reviewed_by", length = 80)
  private String reviewedBy;
  @Column(name = "review_note", length = 500)
  private String reviewNote;

  public ReservationChangeRequest(Reservation reservation, String type,
      ReservationUpdateForm change, String reason, LocalDateTime now) {
    this.reservation = reservation;
    this.requestType = type;
    this.originalDate = reservation.getReservationDate();
    this.originalTime = reservation.getReservationTime();
    this.originalGuests = reservation.getGuestCount();
    this.originalArea = reservation.getPreferredArea();
    this.reason = reason == null ? null : reason.trim();
    this.createdAt = now;
    if (change != null) {
      this.requestedDate = change.getDate();
      this.requestedTime = change.getTime();
      this.requestedGuests = change.getGuests();
      this.requestedArea = change.getPreferredArea() == null ? "" : change.getPreferredArea().trim();
    }
  }

  public ReservationUpdateForm updateForm() {
    var form = new ReservationUpdateForm();
    form.setDate(requestedDate);
    form.setTime(requestedTime);
    form.setGuests(requestedGuests);
    form.setPreferredArea(requestedArea);
    return form;
  }

  public static ReservationChangeRequest preorder(Reservation booking, String payload, LocalDateTime now) {
    var request = new ReservationChangeRequest(booking, "PREORDER", null, null, now);
    request.preorderPayload = payload;
    return request;
  }

  public void review(boolean approved, String reviewer, String note, LocalDateTime now) {
    status = approved ? "APPROVED" : "REJECTED";
    reviewedBy = reviewer;
    reviewNote = note == null ? null : note.trim();
    reviewedAt = now;
  }

  public String getTypeLabel() {
    return switch (requestType) {
      case "CANCEL" -> "Yêu cầu hủy đặt bàn";
      case "PREORDER" -> "Yêu cầu đổi món đặt trước";
      default -> "Yêu cầu thay đổi lịch";
    };
  }

  public String getStatusLabel() {
    return switch (status) {
      case "PENDING" -> "Chờ nhân viên duyệt";
      case "APPROVED" -> "Đã duyệt";
      case "REJECTED" -> "Đã từ chối";
      default -> status;
    };
  }
}
