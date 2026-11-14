package t3l.restaurant.t3l_restaurant.service;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import t3l.restaurant.t3l_restaurant.dto.DishForm;
import t3l.restaurant.t3l_restaurant.entity.Dish;
import t3l.restaurant.t3l_restaurant.repository.DishRepository;

@Service
public class DishService {
  private final DishRepository dishes;
  private final CloudinaryImageService images;

  public DishService(DishRepository dishes, CloudinaryImageService images) {
    this.dishes = dishes;
    this.images = images;
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
  @Transactional
  public Dish save(Long id, DishForm form) {
    Dish dish = id == null ? new Dish() : findById(id);
    String imageUrl = dish.getImageUrl();
    String imagePublicId = dish.getImagePublicId();
    String previousPublicId = imagePublicId;
    MultipartFile image = form.getImage();
    if (image != null && !image.isEmpty()) {
      CloudinaryImageService.UploadedImage uploaded = images.upload(image);
      imageUrl = uploaded.url();
      imagePublicId = uploaded.publicId();
    } else if (id == null) {
      throw new IllegalArgumentException("Vui lòng chọn ảnh món ăn để tải lên Cloudinary.");
    }
    dish.update(
        form.getName().trim(),
        form.getDescription().trim(),
        form.getPrice(),
        imageUrl,
        imagePublicId,
        form.getDietaryLabel(),
        form.getCategoryName().trim(),
        form.isAvailable());
    Dish saved = dishes.save(dish);
    if (image != null && !image.isEmpty() && previousPublicId != null
        && !previousPublicId.equals(imagePublicId)) {
      images.delete(previousPublicId);
    }
    return saved;
  }

  @PreAuthorize("hasRole('ADMIN')")
  @Transactional
  public void delete(Long id) {
    Dish dish = findById(id);
    dishes.delete(dish);
    images.delete(dish.getImagePublicId());
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
    return f;
  }
}
