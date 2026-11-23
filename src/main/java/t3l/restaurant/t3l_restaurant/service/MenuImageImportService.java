package t3l.restaurant.t3l_restaurant.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import t3l.restaurant.t3l_restaurant.entity.Dish;
import t3l.restaurant.t3l_restaurant.repository.DishRepository;

@Service
public class MenuImageImportService {
  private final DishRepository dishes;
  private final CloudinaryImageService images;

  public MenuImageImportService(DishRepository dishes, CloudinaryImageService images) {
    this.dishes = dishes;
    this.images = images;
  }

  @Transactional
  public ImportSummary importCatalog(Path imageDirectory, Path catalogFile) {
    List<CatalogItem> items = readCatalog(catalogFile);
    Path root = imageDirectory.toAbsolutePath().normalize();
    int created = 0;
    int updated = 0;
    int skipped = 0;
    for (CatalogItem item : items) {
      Path imagePath = root.resolve(item.fileName()).normalize();
      if (!imagePath.startsWith(root)) {
        throw new IllegalStateException("Đường dẫn ảnh không hợp lệ trong catalog: " + item.fileName());
      }
      if (!Files.isRegularFile(imagePath)) {
        skipped++;
        continue;
      }
      Dish dish = dishes.findByName(item.name()).orElseGet(Dish::new);
      boolean isNew = dish.getId() == null;
      String publicId = baseName(item.fileName());
      CloudinaryImageService.UploadedImage uploaded = images.upload(imagePath.toFile(), publicId);
      dish.update(
          item.name(),
          item.description(),
          BigDecimal.valueOf(item.price()),
          uploaded.url(),
          uploaded.publicId(),
          item.dietaryLabel(),
          item.category(),
          true);
      dishes.save(dish);
      if (isNew) {
        created++;
      } else {
        updated++;
      }
    }
    return new ImportSummary(created + updated, created, updated, skipped);
  }

  private List<CatalogItem> readCatalog(Path catalogFile) {
    try {
      List<String> lines = Files.readAllLines(catalogFile, StandardCharsets.UTF_8);
      List<CatalogItem> items = new ArrayList<>();
      for (int index = 1; index < lines.size(); index++) {
        if (lines.get(index).isBlank()) {
          continue;
        }
        String[] fields = lines.get(index).split("\\t", -1);
        if (fields.length != 6) {
          throw new IllegalStateException("Sai định dạng catalog tại dòng " + (index + 1));
        }
        items.add(
            new CatalogItem(
                fields[0].trim(),
                fields[1].trim(),
                fields[2].trim(),
                Long.parseLong(fields[3].trim()),
                fields[4].trim(),
                fields[5].trim()));
      }
      if (items.isEmpty()) {
        throw new IllegalStateException("Catalog món ăn đang trống.");
      }
      return items;
    } catch (IOException exception) {
      throw new IllegalStateException("Không đọc được catalog " + catalogFile + ".", exception);
    }
  }

  private String baseName(String fileName) {
    int extensionIndex = fileName.lastIndexOf('.');
    return extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
  }

  private record CatalogItem(
      String fileName,
      String name,
      String category,
      long price,
      String dietaryLabel,
      String description) {}

  public record ImportSummary(int total, int created, int updated, int skipped) {}
}
