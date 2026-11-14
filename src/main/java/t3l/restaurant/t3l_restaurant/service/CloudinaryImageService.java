package t3l.restaurant.t3l_restaurant.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import java.io.File;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CloudinaryImageService {
  private static final long MAX_IMAGE_SIZE = 5 * 1024 * 1024;

  private final String cloudinaryUrl;

  public CloudinaryImageService(@Value("${CLOUDINARY_URL:}") String cloudinaryUrl) {
    this.cloudinaryUrl = cloudinaryUrl;
  }

  public UploadedImage upload(MultipartFile image) {
    validate(image);
    if (cloudinaryUrl.isBlank()) {
      throw new IllegalStateException(
          "Chưa cấu hình CLOUDINARY_URL trong file .env. Hãy thêm thông tin Cloudinary rồi khởi động lại.");
    }

    try {
      Cloudinary cloudinary = new Cloudinary(cloudinaryUrl);
      Map<?, ?> result =
          cloudinary
              .uploader()
              .upload(
                  image.getBytes(),
                  ObjectUtils.asMap("folder", "t3l-restaurant/dishes", "resource_type", "image"));
      return new UploadedImage(
          result.get("secure_url").toString(), result.get("public_id").toString());
    } catch (Exception exception) {
      throw new IllegalStateException("Không tải được ảnh lên Cloudinary. Vui lòng thử lại.", exception);
    }
  }

  public UploadedImage upload(File image, String publicId) {
    if (image == null || !image.isFile()) {
      throw new IllegalArgumentException("Không tìm thấy file ảnh trong thư mục img.");
    }
    if (image.length() > MAX_IMAGE_SIZE) {
      throw new IllegalArgumentException("Ảnh " + image.getName() + " vượt quá 5 MB.");
    }
    if (!image.getName().toLowerCase().matches(".*\\.(png|jpe?g|webp)$")) {
      throw new IllegalArgumentException("Định dạng ảnh không được hỗ trợ: " + image.getName());
    }
    if (cloudinaryUrl.isBlank()) {
      throw new IllegalStateException("Chưa cấu hình CLOUDINARY_URL trong file .env.");
    }

    try {
      Map<?, ?> result =
          new Cloudinary(cloudinaryUrl)
              .uploader()
              .upload(
                  image,
                  ObjectUtils.asMap(
                      "folder", "t3l-restaurant/menu",
                      "public_id", publicId,
                      "overwrite", true,
                      "invalidate", true,
                      "resource_type", "image"));
      return new UploadedImage(
          result.get("secure_url").toString(), result.get("public_id").toString());
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Không tải được " + image.getName() + " lên Cloudinary.", exception);
    }
  }

  public void delete(String publicId) {
    if (publicId == null || publicId.isBlank() || cloudinaryUrl.isBlank()) {
      return;
    }
    try {
      new Cloudinary(cloudinaryUrl).uploader().destroy(publicId, ObjectUtils.emptyMap());
    } catch (Exception ignored) {
      // The database record is already saved; an orphaned Cloudinary asset can be removed later.
    }
  }

  private void validate(MultipartFile image) {
    if (image == null || image.isEmpty()) {
      throw new IllegalArgumentException("Vui lòng chọn ảnh món ăn.");
    }
    if (image.getSize() > MAX_IMAGE_SIZE) {
      throw new IllegalArgumentException("Ảnh phải có dung lượng tối đa 5 MB.");
    }
    String contentType = image.getContentType();
    if (!("image/jpeg".equals(contentType)
        || "image/png".equals(contentType)
        || "image/webp".equals(contentType))) {
      throw new IllegalArgumentException("Chỉ hỗ trợ ảnh JPEG, PNG hoặc WebP.");
    }
  }

  public record UploadedImage(String url, String publicId) {}
}
