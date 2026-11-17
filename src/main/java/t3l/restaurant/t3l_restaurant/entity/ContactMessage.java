package t3l.restaurant.t3l_restaurant.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import t3l.restaurant.t3l_restaurant.dto.ContactForm;

@Entity
@Table(name = "contact_messages", indexes = @Index(name = "idx_contact_status_created", columnList = "status,created_at"))
@Getter
@NoArgsConstructor
public class ContactMessage {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Version private long version;
  @Column(nullable = false, length = 120) private String name;
  @Column(nullable = false, length = 254) private String email;
  @Column(nullable = false, length = 30) private String phone;
  @Column(nullable = false, length = 150) private String subject;
  @Column(nullable = false, length = 4000) private String message;
  @Column(nullable = false, length = 20) private String status = "NEW";
  @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
  @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
  @Column(name = "handled_by", length = 100) private String handledBy;
  @Column(name = "admin_note", length = 1000) private String adminNote;

  public ContactMessage(ContactForm form, LocalDateTime now) {
    name = form.getName().trim(); email = form.getEmail().trim(); phone = form.getPhone().trim();
    subject = form.getSubject().trim(); message = form.getMessage().trim();
    createdAt = now; updatedAt = now;
  }

  public void handle(String status, String note, String admin, LocalDateTime now) {
    this.status = status; adminNote = note == null ? "" : note.trim(); handledBy = admin; updatedAt = now;
  }

  public String getStatusLabel() {
    return switch (status) {
      case "IN_PROGRESS" -> "Đang xử lý";
      case "RESOLVED" -> "Đã xử lý";
      default -> "Mới tiếp nhận";
    };
  }
}
