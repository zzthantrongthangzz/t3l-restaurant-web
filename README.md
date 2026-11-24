# T3L Restaurant

Website nhà hàng và đặt bàn trực tuyến trong phạm vi môn Lập trình WWW Java. Dự án chuyển các luồng đặt bàn, gọi món và vận hành từ [ứng dụng desktop của nhóm](https://github.com/zzthantrongthangzz/RestaurantReservationManagement_T3L) sang web cho một nhà hàng.

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

Theo hướng dẫn thực hành, Spring MVC nhận yêu cầu tại controller, kiểm tra DTO/form, xử lý nghiệp vụ trong service và truy cập dữ liệu qua repository. Thymeleaf kết xuất giao diện trên máy chủ. Spring Security dùng session cho web và Bearer JWT cho các API. Có thể thử `GET /api/menu` và `POST /api/auth/login` bằng Postman.

## Nghiệp vụ và phân quyền

- Khách vãng lai xem trang chủ, thực đơn, FAQ và kiểm tra bàn trống theo ngày, giờ đến. Số điện thoại là bắt buộc khi gửi yêu cầu giữ chỗ. Yêu cầu ở trạng thái **Chờ xác nhận** đến khi nhân viên xử lý.
- Khách có tài khoản (`BUYER`) chọn bàn trước. Sau khi lượt đặt bàn được lưu, khách có thể mở thực đơn từ trang kết quả để thêm món vào đơn của lượt đó. Khách cũng có thể xem lịch sử và gửi yêu cầu đổi hoặc hủy. Cấu hình Google hoặc SMS trong `.env` để sử dụng phương thức đăng nhập tương ứng.
- Nhân viên (`STAFF`) duyệt yêu cầu, check-in, chuyển bàn, gọi món, ghi nhận thanh toán và đối soát ca.
- Quản trị viên (`ADMIN`) dùng trang chủ và thực đơn chung, đồng thời có bảng điều khiển cho tài khoản, bàn, báo cáo doanh thu, hóa đơn, ca, liên hệ, cấu hình và nhật ký. Biểu tượng bút chì trên từng món mở trang chỉnh sửa. Món ẩn và món hết bán vẫn hiển thị cho admin.
- Nút mặt trăng/mặt trời đổi giữa giao diện sáng trắng–cam và tối xám xanh–san hô. Trình duyệt lưu tùy chọn khi chuyển trang.
- Tài khoản nhân viên/quản trị đăng nhập tại `/staff/login`. Đặt tên tài khoản và mật khẩu demo trong `.env` theo `.env.example`.

## Cấu hình Cloudinary

1. Tạo tài khoản Cloudinary và lấy API URL từ trang API Keys/Settings. URL có dạng `cloudinary://API_KEY:API_SECRET@CLOUD_NAME`.
2. Mở file `.env` ở thư mục gốc (file này đã bị loại khỏi Git) và thêm:

   ```properties
   CLOUDINARY_URL=cloudinary://API_KEY:API_SECRET@CLOUD_NAME
   ```

3. Khởi động lại Spring Boot. Đăng nhập admin, mở **Thực đơn**, rồi chọn **Thêm món** hoặc biểu tượng bút chì trên một món. Chọn tệp JPEG/PNG/WebP tối đa 5 MB và lưu.

Ảnh được tải lên từ backend qua Cloudinary Java SDK. MySQL lưu `image_url` và `image_public_id`. Bộ ảnh thực đơn mẫu dùng URL công khai đã lưu trong `17_menu_images.sql`. Chỉ người cần tải hoặc thay ảnh mới cần cấu hình `CLOUDINARY_URL`.

## Thành viên chạy dự án

Yêu cầu: JDK 21 và Docker Desktop.

```powershell
git clone --branch versionb https://github.com/zzthantrongthangzz/t3l-restaurant-web.git
cd t3l-restaurant-web
Copy-Item .env.example .env
# Mở .env, đặt MYSQL_PASSWORD và MYSQL_ROOT_PASSWORD trước lần đầu khởi tạo.
docker compose up -d --wait mysql mongodb
.\mvnw.cmd spring-boot:run
```

Mở <http://localhost:8085/menu>. MySQL dùng cổng `3307`, MongoDB dùng cổng `27018`. Khi tạo volume mới, Docker nạp 54 món, link ảnh Cloudinary và FAQ mẫu. Nếu đã có volume, Docker giữ dữ liệu hiện tại. Chạy các migration còn thiếu theo thứ tự, gồm `17_menu_images.sql` để bổ sung link ảnh còn thiếu.

Thành viên đã clone dùng `git switch versionb` và `git pull --ff-only`. Nếu database đã có danh mục 54 món, có thể nạp riêng link ảnh sau khi cập nhật container MySQL:

```powershell
docker compose up -d --wait mysql
docker compose exec mysql mysql -uroot -p --default-character-set=utf8mb4
```

Nhập `MYSQL_ROOT_PASSWORD` khi được hỏi, rồi chạy `SOURCE /docker-entrypoint-initdb.d/17_menu_images.sql;` tại dấu nhắc MySQL.

Mỗi thành viên dùng `.env` riêng. Các giá trị MySQL trong file đó được dùng cho cả Docker Compose và Spring Boot. Đặt mật khẩu demo của nhân viên/admin qua `APP_STAFF_PASSWORD` và `APP_ADMIN_PASSWORD`, rồi đăng nhập tại `/staff/login`. Khi cần thử Buyer trên máy cá nhân, bật `SMS_OTP_LOCAL_ENABLED=true` và dùng số ở `SMS_OTP_LOCAL_PHONE`.

Nếu cần cùng nhìn thấy các lượt đặt và thay đổi thực đơn mới phát sinh, dùng MySQL và MongoDB dành riêng cho nhóm. Chia sẻ trực tiếp `MYSQL_URL`, `MYSQL_USER`, `MYSQL_PASSWORD` và `MONGODB_URI` qua kênh riêng. Để các thành viên khởi tạo cùng một bản dữ liệu trên máy riêng, có thể gửi bản backup demo đã lọc thông tin khách hàng. Chỉ đưa `.env.example` và dữ liệu mẫu vào Git.

## Script database

- `database/mysql/01_schema_seed.sql`: cấu trúc cho cài mới, 18 bàn và 3 yêu cầu đặt bàn mẫu; Docker chạy tiếp `04` để đồng bộ danh mục 54 món.
- `database/mysql/16_table_kinds.sql` chuẩn hóa loại bàn. Chạy sau bản nâng cấp `14_admin_workspace.sql` nếu đang dùng database đã tạo trước bản này. Docker chạy tập lệnh khi khởi tạo database mới.
- `database/mysql/02_single_restaurant_migration.sql`: chuyển database cũ có chi nhánh/khu sang mô hình một nhà hàng; giữ một bản của mỗi mã bàn và trỏ reservation cũ sang bàn đó.
- `database/mysql/03_sample_data.sql`: nạp bàn, món và reservation mẫu cho database đã migrate.
- `database/mysql/04_menu_catalog_seed.sql`: đồng bộ 54 món ăn/nước theo danh mục ảnh; dọn món cũ ngoài danh mục và an toàn khi chạy lại. Nếu món cũ đã có giao dịch bán hàng, khóa ngoại sẽ giữ dữ liệu để bảo toàn lịch sử.
- `database/mysql/17_menu_images.sql` bổ sung ảnh Cloudinary cho 54 món đã có trong danh mục. Ảnh đã được cập nhật riêng trên từng máy được giữ lại.
- `database/mysql/05_clear_menu_images.sql`: gỡ URL/ID ảnh khỏi các món để hiển thị ảnh minh họa trong lúc chuẩn bị ảnh mới.
- `database/menu-image-catalog.tsv`: ánh xạ tên file ảnh sang tên món, danh mục, mô tả và giá.
- `database/mongodb/01_faq_seed.js`: dữ liệu FAQ mẫu.

Khi bộ ảnh mới sẵn sàng, đặt các file theo tên trong `database/menu-image-catalog.tsv` vào thư mục `img`. Với database đã tồn tại, chạy script danh mục món trước. Sau khi cấp quyền upload cho API key Cloudinary, nạp hoặc đồng bộ ảnh trong một lần:

```powershell
.\mvnw.cmd -q '-Dmaven.test.skip=true' package
java -jar target\t3l-restaurant-1.0.0.jar --server.port=0 --app.import-menu-images=true
```

Chờ log cho biết số ảnh đã nhập, sau đó dừng tiến trình bằng `Ctrl+C`. Importer dùng public ID cố định theo tên file để cập nhật ảnh của món hiện có. Có thể tải từng ảnh ngay từ thực đơn chung.

Với database cũ có chi nhánh, sao lưu trước khi chạy migration `02`, sau đó chạy `03` và các migration còn thiếu. Đối với cài mới, Docker tự chạy các script được khai báo trong Compose.

Chạy Mongo seed thủ công:

```powershell
mongosh "mongodb://localhost:27018/t3l_restaurant" database/mongodb/01_faq_seed.js
```

Maven/IntelliJ: mở thư mục gốc hoặc `pom.xml`, dùng JDK 21, sau đó chọn **Reload All Maven Projects**. Nếu Lombok chưa nhận getter/setter tự sinh, bật Annotation Processing trong cấu hình IntelliJ.
