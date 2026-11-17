package t3l.restaurant.t3l_restaurant.service;

import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.dto.ContactForm;
import t3l.restaurant.t3l_restaurant.entity.ContactMessage;
import t3l.restaurant.t3l_restaurant.repository.ContactMessageRepository;

@Service
public class ContactService {
  public static final Set<String> STATUSES = Set.of("NEW", "IN_PROGRESS", "RESOLVED");
  private final ContactMessageRepository messages;
  private final ReservationSchedule schedule;
  private final Validator validator;

  public ContactService(ContactMessageRepository messages, ReservationSchedule schedule, Validator validator) {
    this.messages = messages; this.schedule = schedule; this.validator = validator;
  }

  @Transactional
  public ContactMessage submit(ContactForm form) {
    validator.validate(form).stream().findFirst().ifPresent(error -> {
      throw new IllegalArgumentException(error.getMessage());
    });
    return messages.saveAndFlush(new ContactMessage(form, LocalDateTime.now(schedule.getClock())));
  }

  @Transactional(readOnly = true)
  public Page<ContactMessage> list(String status, int page) {
    if (status != null && !status.isBlank() && !STATUSES.contains(status)) throw new IllegalArgumentException("Bộ lọc không hợp lệ.");
    var pageable = PageRequest.of(Math.max(0, page), 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    return status == null || status.isBlank() ? messages.findAll(pageable) : messages.findByStatus(status, pageable);
  }

  @Transactional(readOnly = true)
  public ContactMessage find(Long id) {
    return messages.findById(id).orElseThrow(() -> new IllegalArgumentException("Không tìm thấy liên hệ."));
  }

  @Transactional
  public void update(Long id, long version, String status, String note, String admin) {
    if (!STATUSES.contains(status)) throw new IllegalArgumentException("Trạng thái liên hệ không hợp lệ.");
    if (note != null && note.length() > 1000) throw new IllegalArgumentException("Ghi chú không vượt quá 1.000 ký tự.");
    var message = find(id);
    if (message.getVersion() != version) throw new IllegalArgumentException("Liên hệ đã được cập nhật. Vui lòng tải lại trước khi lưu.");
    message.handle(status, note, admin, LocalDateTime.now(schedule.getClock()));
    messages.flush();
  }
}
