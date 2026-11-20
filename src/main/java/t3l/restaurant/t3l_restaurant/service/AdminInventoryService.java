package t3l.restaurant.t3l_restaurant.service;

import jakarta.validation.Validator;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.dto.AdminTableForm;
import t3l.restaurant.t3l_restaurant.entity.RestaurantSection;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;
import t3l.restaurant.t3l_restaurant.repository.DishRepository;
import t3l.restaurant.t3l_restaurant.repository.ReservationChangeRequestRepository;
import t3l.restaurant.t3l_restaurant.repository.ReservationRepository;
import t3l.restaurant.t3l_restaurant.repository.RestaurantSectionRepository;
import t3l.restaurant.t3l_restaurant.repository.RestaurantTableRepository;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminInventoryService {
  private final RestaurantTableRepository tables;
  private final RestaurantSectionRepository sections;
  private final DishRepository dishes;
  private final ReservationRepository reservations;
  private final ReservationChangeRequestRepository requests;
  private final ReservationService bookings;
  private final Validator validator;
  private final AdminAudit audit;

  public record SectionRow(Long id, String name, boolean active) {}

  @Transactional(readOnly = true)
  public List<SectionRow> sectionList(String kind) {
    var rows = new TreeMap<String, SectionRow>(String.CASE_INSENSITIVE_ORDER);
    if (RestaurantSection.MENU.equals(kind)) {
      dishes.categoryNames().forEach(name -> rows.put(name, new SectionRow(null, name, true)));
    } else {
      tables.findAllByOrderByTableTypeAscCodeAsc().forEach(table ->
          rows.put(table.getTableType(), new SectionRow(null, table.getTableType(), true)));
    }
    sections.findByKindOrderByNameAsc(kind).forEach(section ->
        rows.put(section.getName(), new SectionRow(section.getId(), section.getName(), section.isActive())));
    return List.copyOf(rows.values());
  }

  @Transactional(readOnly = true)
  public List<RestaurantTable> tables() { return tables.findAllByOrderByTableTypeAscCodeAsc(); }

  @Transactional(readOnly = true)
  public AdminTableForm tableForm(Long id) {
    var table = tables.findById(id).orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bàn."));
    var form = new AdminTableForm();
    form.setCode(table.getCode());
    form.setArea(table.getTableType());
    form.setKind(table.getTableKind());
    form.setCapacity(table.getCapacity());
    form.setActive(table.isActive());
    return form;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void saveTable(Long id, AdminTableForm form) {
    validator.validate(form).stream().findFirst().ifPresent(error -> { throw new IllegalArgumentException(error.getMessage()); });
    var inventory = tables.lockInventory();
    var knownAreas = sections.lockByKind(RestaurantSection.AREA);
    String area = form.getArea().strip();
    var configured = knownAreas.stream().filter(item -> item.getName().equalsIgnoreCase(area)).findFirst().orElse(null);
    String canonicalArea = configured == null
        ? inventory.stream().map(RestaurantTable::getTableType).filter(area::equalsIgnoreCase).findFirst().orElse(null)
        : configured.getName();
    if (canonicalArea == null) throw new IllegalArgumentException("Hãy chọn khu vực đã được tạo.");
    String code = form.getCode().strip();
    if (inventory.stream().anyMatch(table -> !Objects.equals(id, table.getId()) && code.equalsIgnoreCase(table.getCode()))) {
      throw new IllegalArgumentException("Mã bàn đã được dùng.");
    }
    var table = id == null ? new RestaurantTable()
        : inventory.stream().filter(item -> item.getId().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bàn."));
    if (id != null && (!table.getCode().equals(code) || !table.getTableType().equals(canonicalArea)
        || form.getCapacity() < table.getCapacity() || (table.isActive() && !form.isActive())) && bookings.tableInUse(id)) {
      throw new IllegalArgumentException("Bàn đang phục vụ hoặc giữ chỗ. Hãy xử lý các lượt đặt trước khi đổi mã, khu vực, giảm sức chứa hoặc ngừng sử dụng.");
    }
    table.configure(code, canonicalArea, AdminInput.optional(form.getKind()), form.getCapacity(), form.isActive());
    tables.save(table);
    audit.record(id == null ? "Tạo bàn" : "Cập nhật bàn", code + " · " + canonicalArea);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void saveSection(String kind, Long id, String currentName, String name, boolean active) {
    boolean area = RestaurantSection.AREA.equals(kind);
    if (!area && !RestaurantSection.MENU.equals(kind)) throw new IllegalArgumentException("Loại danh mục không hợp lệ.");
    String next = name.strip();
    if (next.isEmpty() || next.length() > (area ? 40 : 80)) {
      throw new IllegalArgumentException("Tên khu vực hoặc danh mục không hợp lệ.");
    }
    var inventory = area ? tables.lockInventory() : List.<RestaurantTable>of();
    var existing = sections.lockByKind(kind);
    var section = id == null ? null : existing.stream().filter(row -> row.getId().equals(id)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khu vực hoặc danh mục."));
    var legacyNames = area ? inventory.stream().map(RestaurantTable::getTableType).distinct().toList() : dishes.categoryNames();
    String previous = section == null ? legacyNames.stream()
        .filter(value -> value.equalsIgnoreCase(currentName)).findFirst().orElse(null) : section.getName();
    if (section == null && previous != null && existing.stream().anyMatch(row -> row.getName().equalsIgnoreCase(previous))) {
      throw new IllegalArgumentException("Danh mục vừa được cập nhật. Vui lòng tải lại.");
    }
    if (existing.stream().anyMatch(row -> !Objects.equals(id, row.getId()) && row.getName().equalsIgnoreCase(next))
        || legacyNames.stream().anyMatch(value -> !value.equalsIgnoreCase(previous) && value.equalsIgnoreCase(next))) {
      throw new IllegalArgumentException("Tên này đã được sử dụng.");
    }
    if (area && !active && previous != null && inventory.stream().filter(table -> table.getTableType().equalsIgnoreCase(previous))
        .anyMatch(table -> bookings.tableInUse(table.getId()))) {
      throw new IllegalArgumentException("Khu vực có bàn đang phục vụ hoặc giữ chỗ. Hãy xử lý các lượt đặt trước.");
    }
    if (section == null) section = new RestaurantSection(kind, next, active);
    else section.update(next, active);
    sections.save(section);
    if (previous != null && !previous.equals(next)) {
      if (area) {
        tables.renameArea(previous, next);
        reservations.renamePreferredArea(previous, next);
        requests.renameOriginalArea(previous, next);
        requests.renameRequestedArea(previous, next);
      } else dishes.renameCategory(previous, next);
    }
    audit.record(area ? "Cập nhật khu vực" : "Cập nhật danh mục",
        (previous != null && !previous.equals(next) ? previous + " → " : "") + next + (active ? " · Hoạt động" : " · Tạm ngưng"));
  }
}
