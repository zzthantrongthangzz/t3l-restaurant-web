package t3l.restaurant.t3l_restaurant.service;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import t3l.restaurant.t3l_restaurant.dto.DishForm;
import t3l.restaurant.t3l_restaurant.entity.Dish;
import t3l.restaurant.t3l_restaurant.repository.DishRepository;
import t3l.restaurant.t3l_restaurant.repository.CashSaleRepository;
import t3l.restaurant.t3l_restaurant.repository.DiningOrderItemRepository;
import t3l.restaurant.t3l_restaurant.repository.RestaurantSectionRepository;

@Service
public class DishService {
  private final DishRepository dishes;
  private final CloudinaryImageService images;
  private final RestaurantSectionRepository sections;
  private final CashSaleRepository sales;
  private final DiningOrderItemRepository orderItems;
  private final AdminAudit audit;

  public DishService(DishRepository dishes, CloudinaryImageService images, RestaurantSectionRepository sections,
      CashSaleRepository sales, DiningOrderItemRepository orderItems, AdminAudit audit) {
    this.dishes = dishes;
    this.images = images;
    this.sections = sections;
    this.sales = sales;
    this.orderItems = orderItems;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<Dish> findAll() {
    return dishes.findAll(Sort.by("categoryName").and(Sort.by("name")));
  }

  @Transactional(readOnly = true)
  public List<Dish> findAvailable() {
    return dishes.findByAvailableTrueOrderByCategoryNameAscNameAsc();
  }

  @Transactional(readOnly = true)
  public Dish findById(Long id) {
    return dishes
        .findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy món ăn."));
  }

  @PreAuthorize("hasRole('ADMIN')")
  @Transactional(readOnly = true)
  public Page<Dish> search(String text, String category, String stock, String visibility, String order, int page) {
    var sort = switch (order) {
      case "price-asc" -> Sort.by("price", "name");
      case "price-desc" -> Sort.by(Sort.Direction.DESC, "price").and(Sort.by("name"));
      case "name" -> Sort.by("name");
      default -> Sort.by("categoryName", "name");
    };
    return dishes.search(AdminInput.like(text), category, flag(stock, "available", "sold-out"),
        flag(visibility, "published", "hidden"), PageRequest.of(Math.max(0, page), 20, sort.and(Sort.by("id"))));
  }

  private Boolean flag(String value, String yes, String no) {
    if (yes.equals(value)) return true;
    if (no.equals(value)) return false;
    return null;
  }

  @PreAuthorize("hasRole('ADMIN')")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Dish save(Long id, DishForm form) {
    var categories = sections.lockByKind("MENU");
    Dish dish = id == null ? new Dish() : findById(id);
    String requestedCategory = form.getCategoryName().strip();
    String category = categories.stream().map(item -> item.getName()).filter(requestedCategory::equalsIgnoreCase).findFirst()
        .orElseGet(() -> dishes.categoryNames().stream().filter(requestedCategory::equalsIgnoreCase).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Hãy chọn danh mục đã được tạo.")));
    String imageUrl = dish.getImageUrl();
    String imagePublicId = dish.getImagePublicId();
    String previousPublicId = imagePublicId;
    MultipartFile image = form.getImage();
    String directUrl = AdminInput.optional(form.getImageUrl());
    if (image != null && !image.isEmpty()) {
      CloudinaryImageService.UploadedImage uploaded = images.upload(image);
      imageUrl = uploaded.url();
      imagePublicId = uploaded.publicId();
      finishImageChange(previousPublicId, imagePublicId);
    } else if (form.getImageUrl() != null && !java.util.Objects.equals(directUrl, imageUrl)) {
      AdminInput.validateImage(directUrl);
      imageUrl = directUrl;
      imagePublicId = null;
      finishImageChange(previousPublicId, null);
    }
    dish.update(
        form.getName().trim(),
        form.getDescription().trim(),
        form.getPrice(),
        imageUrl,
        imagePublicId,
        form.getDietaryLabel(),
        category,
        form.isAvailable());
    dish.setPublished(form.isPublished());
    audit.record(id == null ? "Thêm món" : "Cập nhật món", dish.getName());
    return dishes.save(dish);
  }

  @PreAuthorize("hasRole('ADMIN')")
  @Transactional
  public void delete(Long id, String reason) {
    Dish dish = findById(id);
    audit.record("Xóa món", "DISH", id, dish.getName(),
        AdminAudit.withReason("Đã xóa món khỏi danh mục " + dish.getCategoryName() + ". Giá trước khi xóa "
            + dish.getPrice().stripTrailingZeros().toPlainString() + " đ.", reason));
    if (sales.existsByDish_Id(id) || orderItems.existsByDishId(id)) {
      throw new IllegalArgumentException("Món đã có trong giao dịch. Bạn có thể ẩn món khỏi thực đơn.");
    }
    dishes.delete(dish);
    finishImageChange(dish.getImagePublicId(), null);
  }

  @PreAuthorize("hasRole('ADMIN')")
  @Transactional
  public void setMenuState(Long id, boolean value, boolean stock) {
    Dish dish = findById(id);
    if (stock) dish.setAvailable(value);
    else dish.setPublished(value);
    audit.record(stock ? (value ? "Mở bán món" : "Đánh dấu hết món") : (value ? "Hiện món" : "Ẩn món"), dish.getName());
  }

  private void finishImageChange(String oldId, String newId) {
    if (java.util.Objects.equals(oldId, newId)) return;
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCommit() { images.delete(oldId); }
      @Override public void afterCompletion(int status) {
        if (status == STATUS_ROLLED_BACK) images.delete(newId);
      }
    });
  }

  @Transactional(readOnly = true)
  public DishForm toForm(Dish dish) {
    DishForm f = new DishForm();
    f.setName(dish.getName());
    f.setDescription(dish.getDescription());
    f.setPrice(dish.getPrice());
    f.setDietaryLabel(dish.getDietaryLabel());
    f.setCategoryName(dish.getCategoryName());
    f.setAvailable(dish.isAvailable());
    f.setPublished(dish.isPublished());
    f.setImageUrl(dish.getImageUrl());
    return f;
  }
}
