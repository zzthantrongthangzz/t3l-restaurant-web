# T3L Restaurant

Website nhà hàng và đặt bàn trực tuyến trong phạm vi môn Lập trình WWW Java. Luồng đặt bàn được tham khảo từ [project cũ](https://github.com/zzthantrongthangzz/RestaurantReservationManagement_T3L); project này giữ một nhà hàng, không triển khai chi nhánh hoặc khu vực bàn.

## Công nghệ

- **Backend:** Java 21, Maven, Spring Boot 3.3, Spring MVC, Spring Data JPA, Spring Data MongoDB, Spring Security, Bean Validation và Lombok.
- **Frontend:** Thymeleaf, HTML/CSS và JavaScript tĩnh; giao diện được Spring Boot phục vụ. Không cần một ứng dụng Node/React riêng.
- **MySQL:** người dùng/role, món ăn, bàn, đặt bàn, ca làm, giao dịch bán hàng và đối soát tiền mặt.
- **MongoDB:** FAQ và nội dung linh hoạt.
- **Cloudinary:** nơi lưu file ảnh món ăn, đồng thời cấp URL HTTPS và public ID. Cloudinary là dịch vụ lưu trữ media/CDN, **không** tính là cơ sở dữ liệu thứ hai. Yêu cầu “ít nhất hai database” được đáp ứng bằng MySQL + MongoDB.

## Cấu trúc

```text
D:\t3l-restaurant-web\
├── .mvn/wrapper/                 # Maven Wrapper
├── database/
│   ├── mysql/                    # Schema, seed và migration MySQL
│   └── mongodb/                  # Seed MongoDB
├── src/main/java/...             # Controller, service, entity, repository, security
├── src/main/resources/
│   ├── templates/                # Thymeleaf: web, auth, admin, staff
│   ├── static/                   # CSS, JavaScript, tài nguyên giao diện
│   └── application.properties
├── .env.example
├── docker-compose.yml
├── mvnw.cmd
└── pom.xml                       # Maven project duy nhất
```

Frontend Thymeleaf nằm trong `src/main/resources/templates`; backend nằm trong `src/main/java`. Đây là một ứng dụng Spring Boot MVC theo hướng dẫn thực hành, không tách thành hai project frontend/backend.

## Nghiệp vụ và phân quyền

- Guest và BUYER xem menu, FAQ, danh sách bàn theo ngày và gửi yêu cầu đặt bàn.
- Mọi ngày chỉ có một lượt giữ bàn. Guest/BUYER chỉ thấy trống/đã đặt; STAFF/ADMIN thấy thêm tên khách đã đặt.
- STAFF/ADMIN duyệt hoặc hủy yêu cầu, mở ca, ghi nhận món bán theo tiền mặt/chuyển khoản/thẻ, nhập tiền thực đếm và kết ca. Báo cáo lưu doanh thu từng phương thức cùng chênh lệch tiền mặt.
- ADMIN quản lý thực đơn và có các thao tác của khách/nhân viên.
- Demo accounts được khởi tạo/cập nhật khi ứng dụng chạy: `admin / Admin@123`, `staff / Staff@123`, `buyer / Buyer@123`. Đổi mật khẩu trong `.env` trước khi chia sẻ project.

## Cấu hình Cloudinary

1. Tạo tài khoản Cloudinary và lấy API URL từ trang API Keys/Settings. URL có dạng `cloudinary://API_KEY:API_SECRET@CLOUD_NAME`.
2. Mở file `.env` ở thư mục gốc (file này đã bị loại khỏi Git) và thêm:

   ```properties
   CLOUDINARY_URL=cloudinary://API_KEY:API_SECRET@CLOUD_NAME
   ```

3. Khởi động lại Spring Boot. Vào **Quản lý → Thực đơn → Thêm món**, chọn tệp JPEG/PNG/WebP tối đa 5 MB và lưu.

Ảnh được tải lên từ backend qua Cloudinary Java SDK. MySQL lưu `image_url` và `image_public_id`; ảnh không được lưu trong thư mục source. Thực đơn hiện dùng ảnh minh họa tạm trong lúc chuẩn bị bộ ảnh mới. Khi chưa nhập Cloudinary URL, phần upload hiển thị hướng dẫn cấu hình thay vì giả lập ảnh bằng đường dẫn máy tính.

## Chạy ứng dụng

Yêu cầu: JDK 21 và Docker Desktop.

```powershell
cd D:\t3l-restaurant-web
docker compose up -d mysql mongodb
.\mvnw.cmd spring-boot:run
```

Mở <http://localhost:8085>. MySQL được publish ở cổng `3307`, MongoDB ở `27018`. Docker tạo schema/dữ liệu tự động khi MySQL chạy trên volume rỗng. Mongo seed FAQ cũng chỉ tự chạy lần đầu trên volume rỗng.

## Script database

- `database/mysql/01_schema_seed.sql`: cấu trúc cho cài mới, 18 bàn và 3 yêu cầu đặt bàn mẫu; Docker chạy tiếp `04` để đồng bộ danh mục 54 món.
- `database/mysql/02_single_restaurant_migration.sql`: chuyển database cũ có chi nhánh/khu sang mô hình một nhà hàng; giữ một bản của mỗi mã bàn và trỏ reservation cũ sang bàn đó.
- `database/mysql/03_sample_data.sql`: nạp bàn, món và reservation mẫu cho database đã migrate.
- `database/mysql/04_menu_catalog_seed.sql`: đồng bộ 54 món ăn/nước theo danh mục ảnh; dọn món cũ ngoài danh mục và an toàn khi chạy lại. Nếu món cũ đã có giao dịch bán hàng, khóa ngoại sẽ giữ dữ liệu để bảo toàn lịch sử.
- `database/mysql/05_clear_menu_images.sql`: gỡ URL/ID ảnh khỏi các món để hiển thị ảnh minh họa trong lúc chuẩn bị ảnh mới.
- `database/menu-image-catalog.tsv`: ánh xạ tên file ảnh sang tên món, danh mục, mô tả và giá.
- `database/mongodb/01_faq_seed.js`: dữ liệu FAQ mẫu.

Khi bộ ảnh mới sẵn sàng, đặt các file theo tên trong `database/menu-image-catalog.tsv` vào thư mục `img`. Với database đã tồn tại, chạy script danh mục món trước. Sau khi cấp quyền upload cho API key Cloudinary, nạp hoặc đồng bộ ảnh trong một lần:

```powershell
.\mvnw.cmd -q '-Dmaven.test.skip=true' package
java -jar target\t3l-restaurant-1.0.0.jar --server.port=0 --app.import-menu-images=true
```

Chờ log `Imported 54 menu images`, sau đó dừng tiến trình bằng `Ctrl+C`. Importer dùng public ID cố định theo tên file nên chạy lại sẽ cập nhật ảnh/món hiện có thay vì tạo món trùng. Admin cũng có thể tự tạo món và upload ảnh riêng từ trang quản lý thực đơn.

Database project cũ đã migrate một lần. Với database cũ khác, sao lưu trước khi chạy migration `02`, sau đó chạy `03`. Database cài mới chỉ cần để Docker chạy `01`. Không xóa Docker volume nếu muốn giữ database.

Chạy Mongo seed thủ công:

```powershell
mongosh "mongodb://localhost:27018/t3l_restaurant" database/mongodb/01_faq_seed.js
```

Maven/IntelliJ: mở thư mục gốc hoặc `pom.xml`, dùng JDK 21, sau đó chọn **Reload All Maven Projects**. Nếu Lombok chưa nhận getter/setter tự sinh, bật Annotation Processing trong cấu hình IntelliJ.
