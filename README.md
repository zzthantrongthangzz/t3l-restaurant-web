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
- Khách có tài khoản (`BUYER`) đăng ký bằng số điện thoại và mật khẩu xác nhận, đăng nhập bằng số điện thoại/mật khẩu hoặc Google. Sau khi lượt đặt bàn được lưu, khách có thể mở thực đơn từ trang kết quả để thêm món vào đơn của lượt đó, xem lịch sử và gửi yêu cầu đổi hoặc hủy.
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
git clone --branch main https://github.com/zzthantrongthangzz/t3l-restaurant-web.git
cd t3l-restaurant-web
Copy-Item .env.example .env
# Mở .env, đặt MYSQL_PASSWORD và MYSQL_ROOT_PASSWORD trước lần đầu khởi tạo.
docker compose up -d --wait mysql mongodb
.\mvnw.cmd spring-boot:run
```

Mở <http://localhost:8085/menu>. MySQL dùng cổng `3307`, MongoDB dùng cổng `27018`. Khi tạo volume mới, Docker nạp 54 món, link ảnh Cloudinary và FAQ mẫu. Nếu đã có volume, Docker giữ dữ liệu hiện tại. Chạy các migration còn thiếu theo thứ tự. Với database đã chạy `versionb`, dùng các script `18_deposit_settlement.sql`, `19_preorder_cart.sql` và `20_sepay_payments.sql` theo thứ tự sau khi cập nhật container.

Thành viên đã clone dùng `git switch main` và `git pull --ff-only`. Với volume MySQL đã tạo, mở dấu nhắc MySQL để chạy các migration còn thiếu:

```powershell
docker compose up -d --wait mysql
docker compose exec mysql mysql -uroot -p --default-character-set=utf8mb4
```

Nhập `MYSQL_ROOT_PASSWORD` khi được hỏi. Ví dụ, với database đã có cấu trúc `versionb` và danh mục món mẫu:

```sql
SOURCE /docker-entrypoint-initdb.d/17_menu_images.sql;
SOURCE /docker-entrypoint-initdb.d/18_deposit_settlement.sql;
SOURCE /docker-entrypoint-initdb.d/19_preorder_cart.sql;
SOURCE /docker-entrypoint-initdb.d/20_sepay_payments.sql;
```

Script 17 đồng bộ link ảnh theo tên món mẫu. Có thể bỏ qua script 17 nếu muốn giữ các ảnh đã thay trên database hiện tại.

Với database đã có bảng `reservation_emails`, chạy `26_reservation_email_checks.sql` để đồng bộ các giá trị hợp lệ của loại và trạng thái email. Với container hiện có, chép riêng script vào container bằng `docker compose cp database/mysql/26_reservation_email_checks.sql mysql:/tmp/26_reservation_email_checks.sql`. Trong dấu nhắc MySQL, chọn database bằng `USE t3l_restaurant;` rồi chạy `SOURCE /tmp/26_reservation_email_checks.sql;`. Có thể chạy lại script. Docker Compose tự nạp script khi khởi tạo volume mới.

Chạy `27_table_capacities.sql` theo cùng cách để chuẩn hóa sức chứa trên database hiện có. Bàn nhỏ có 4 chỗ, bàn vừa 8 chỗ, bàn lớn 16 chỗ và phòng VIP 30 chỗ. Script giữ mã bàn và lịch đặt. Nếu một lượt đặt đang mở vượt sức chứa mới, cần xử lý lượt đó trước khi chạy lại. Docker Compose tự nạp script trên volume mới.

Mỗi thành viên dùng `.env` riêng. Các giá trị MySQL trong file đó được dùng cho cả Docker Compose và Spring Boot. Đặt mật khẩu nhân viên/admin qua `APP_STAFF_PASSWORD` và `APP_ADMIN_PASSWORD`, rồi đăng nhập tại `/staff/login`. Với bản chạy dùng chung, thay `APP_JWT_SECRET` bằng khóa ngẫu nhiên tối thiểu 32 byte, mã hóa Base64.

Nếu cần cùng nhìn thấy các lượt đặt và thay đổi thực đơn mới phát sinh, dùng MySQL và MongoDB dành riêng cho nhóm. Chia sẻ trực tiếp `MYSQL_URL`, `MYSQL_USER`, `MYSQL_PASSWORD` và `MONGODB_URI` qua kênh riêng. Chỉ đưa `.env.example` và dữ liệu giả định vào Git.

MySQL mới tạo bằng Docker Compose tự nạp `database/mysql/23_operating_data.sql` và `database/mysql/25_operating_examples.sql`. Dữ liệu giả định gồm bàn đang phục vụ có lịch tiếp theo, các trạng thái đặt bàn, món và cọc, hóa đơn có voucher, ca đã đối soát, bán lẻ, liên hệ, hồ sơ khách, yêu cầu đổi lịch và lịch sử truy cập. MongoDB mới tự nạp FAQ từ `database/mongodb/01_faq_seed.js`. Giờ phục vụ và ngày báo cáo tính từ lần nạp đầu tiên. Chạy lại giữ các lượt đã sinh, mật khẩu, voucher và câu hỏi đã chỉnh. Admin có thể cấp mật khẩu cho nhân viên bổ sung khi cần.

Với MySQL đã chạy trước đây, sao lưu rồi nạp `23_operating_data.sql` và `25_operating_examples.sql` theo thứ tự. Nếu phiên bản cũ từng tạo sáu bàn thử, chạy `24_consolidate_legacy_inventory.sql` trước để chuyển các lượt cũ về bàn đúng loại. Script dừng nếu bàn nhận thiếu hoặc đang có lịch mở có thể xung đột. Với MongoDB đang có dữ liệu, chạy lại script FAQ để thêm câu hỏi mới.

Sơ đồ nội bộ ngày hiện tại ưu tiên lượt đang phục vụ và hiển thị thêm giờ đặt kế tiếp. Nhân viên vẫn có thể nhận lượt vãng lai nếu toàn bộ thời lượng phục vụ dự kiến kết thúc trước hoặc đúng giờ hẹn. Khách đặt online xem tình trạng theo giờ họ chọn.

### AI ChatBox

ChatBox dùng MySQL cho thực đơn và đặt bàn. MongoDB ở `127.0.0.1:27018` lưu FAQ, lịch sử chat và bộ đếm giới hạn yêu cầu. Để bật tìm kiếm ngữ nghĩa, chạy thêm Atlas Local và điền `GEMINI_API_KEY` cùng `CHAT_VECTOR_MONGODB_URI=mongodb://127.0.0.1:27019/t3l_restaurant?directConnection=true` trong `.env`.

```powershell
docker compose --profile atlas-local up -d mongodb-vector
```

Gemini dùng mặc định `gemini-3.1-flash-lite` và `gemini-embedding-001` với 768 chiều. Ứng dụng tự tạo index `semantic` và chạy đồng bộ định kỳ từ FAQ đã xuất bản cùng thông tin công khai của nhà hàng. Lần chạy đầu có thể mất thời gian để index sẵn sàng. Khi dịch vụ AI chưa sẵn sàng, ChatBox vẫn xử lý được các câu hỏi khớp dữ liệu nhà hàng và các yêu cầu đặt bàn đủ thông tin.

Để chạy cả hai test MongoDB thật, đặt `T3L_CHAT_MONGO_TEST_URI` và `T3L_CHAT_VECTOR_TEST_URI` trỏ vào dịch vụ QA trước khi chạy `.\mvnw.cmd verify`. URI của Atlas phải chứa tên database. Các test này tạo rồi dọn dữ liệu QA riêng.

## Chính sách tiền cọc

- Bàn thường chỉ cần cọc khi đặt món trước. Bàn lớn cọc tối thiểu 300.000 đ, bàn VIP tối thiểu 500.000 đ. Cọc phải trả là mức cao hơn giữa mức tối thiểu của bàn và 70% tổng tiền món đặt trước, làm tròn lên đến đồng. Mức tối thiểu theo loại bàn cũng áp dụng cho khách vãng lai. Khách đến trực tiếp do nhân viên tiếp nhận được miễn cọc.
- Khi dùng đối soát thủ công, sau khi duyệt khách có tối đa 120 phút để gửi mã giao dịch. Nhân viên có tối đa 120 phút để đối soát kể từ khi nhận mã. Cả hai hạn kết thúc muộn nhất vào giờ đến. Khi quá hạn, bàn được giải phóng. Giao dịch đã gửi mà chưa xác nhận được tiếp tục đối soát để hoàn tiền nếu nhà hàng thực nhận.
- Yêu cầu hủy đủ 24 giờ được hoàn 100%, hủy muộn được hoàn 50%, không đến được hoàn 0% theo mặc định. Quy định thời gian gửi yêu cầu đổi/hủy vẫn áp dụng. Nhà hàng hủy hoặc hết hạn giữ bàn hoàn đủ tiền đã nhận. Đổi lịch và chuyển bàn trước check-in tính lại mức cọc, giữ tiền đã nhận để khấu trừ và yêu cầu nộp thêm nếu thiếu. Chuyển bàn lúc đang phục vụ giữ nguyên chính sách đã chốt.
- Điều chỉnh thời hạn và tỷ lệ hoàn trong các biến `DEPOSIT_*` của `.env.example`. Khi hoàn tiền, nhân viên chuyển hoàn bên ngoài hệ thống rồi ghi lại số tiền và mã giao dịch. Doanh thu ghi nhận phần cọc đã khấu trừ tại lúc chốt thanh toán hoặc phần cọc giữ theo chính sách.

### Thanh toán QR SePay

Mặc định cọc và chuyển khoản được nhân viên đối soát thủ công. Để bật QR và tự đối soát cọc, cấu hình `SEPAY_ENABLED=true`, `SEPAY_API_TOKEN`, `SEPAY_BANK_NAME`, `SEPAY_BANK_ACCOUNT` và `SEPAY_ACCOUNT_HOLDER` trong `.env`. Khi bật SePay, yêu cầu cần đủ cọc trước khi xác nhận. Khoản thanh toán hóa đơn bằng QR vẫn cần nhân viên xác nhận sau khi SePay đối soát.

`SEPAY_DEMO_ENABLED` mặc định tắt. Chế độ mô phỏng chỉ hoạt động trong profile local, dev, development, QA hoặc test. Giao dịch DEMO được gắn nhãn và loại khỏi doanh thu thực tế.

## Món đặt trước

- Giỏ nhận tối đa 30 loại món và 20 phần mỗi loại. Giỏ và ghi chú bếp được giữ khi khách đăng nhập, đăng ký hoặc bổ sung hồ sơ.
- Khách có thể gửi yêu cầu đổi món trước thời hạn đổi lịch hiện hành, khi chưa check-in và cọc không trong quá trình đối soát. Nhân viên duyệt giỏ mới sau khi kiểm tra giá và trạng thái bán. Phần đã đặt giữ giá cũ, phần thêm theo giá hiện hành.
- Cọc đã nhận được giữ để đối trừ. Khi cọc mới cao hơn, khách nộp phần thiếu. Món hết được loại khỏi đơn trước khi tính tiền. Đơn đặt trước được tiếp tục khi nhận bàn và được đóng nếu lượt đặt bị hủy hoặc khách không đến.

## Script database

- `database/mysql/01_schema_seed.sql`: cấu trúc cho cài mới, 18 bàn và 3 yêu cầu đặt bàn mẫu; Docker chạy tiếp `04` để đồng bộ danh mục 54 món.
- `database/mysql/16_table_kinds.sql` chuẩn hóa loại bàn. Chạy sau bản nâng cấp `14_admin_workspace.sql` nếu đang dùng database đã tạo trước bản này. Docker chạy tập lệnh khi khởi tạo database mới.
- `database/mysql/02_single_restaurant_migration.sql`: chuyển database cũ có chi nhánh/khu sang mô hình một nhà hàng; giữ một bản của mỗi mã bàn và trỏ reservation cũ sang bàn đó.
- `database/mysql/03_sample_data.sql`: nạp bàn, món và đặt bàn mẫu cho database đã migrate. Chạy lại `27_table_capacities.sql` sau script mẫu để áp dụng sức chứa mới.
- `database/mysql/04_menu_catalog_seed.sql`: đồng bộ 54 món ăn/nước theo danh mục ảnh; dọn món cũ ngoài danh mục và an toàn khi chạy lại. Nếu món cũ đã có giao dịch bán hàng, khóa ngoại sẽ giữ dữ liệu để bảo toàn lịch sử.
- `database/mysql/17_menu_images.sql` bổ sung ảnh Cloudinary cho 54 món đã có trong danh mục. Ảnh đã được cập nhật riêng trên từng máy được giữ lại.
- `database/mysql/05_clear_menu_images.sql`: gỡ URL/ID ảnh khỏi các món để hiển thị ảnh minh họa trong lúc chuẩn bị ảnh mới.
- `database/mysql/15_reservation_deposits.sql`: bổ sung số tiền, trạng thái và mã giao dịch cọc vào đặt bàn.
- `database/mysql/18_deposit_settlement.sql`: bổ sung hạn cọc, ghi nhận hoàn tiền, khấu trừ hóa đơn và mã giao dịch duy nhất. Chạy sau `15` khi nâng cấp một database đã tồn tại.
- `database/mysql/19_preorder_cart.sql`: thêm nhận diện món đặt trước, ghi chú món, ghi chú bếp và yêu cầu đổi món, giữ nguyên dòng hóa đơn cũ.
- `database/mysql/20_sepay_payments.sql`: thêm bảng yêu cầu thanh toán QR và cờ giao dịch DEMO. Chạy sau `18` và `19`.
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

## Phân tích hành vi khách hàng

Quản trị viên xem báo cáo tại `/admin/analytics`. Với volume MySQL đã có, sao lưu database trước khi áp dụng `database/mysql/21_user_tracking.sql`. Docker Compose tự nạp migration khi khởi tạo volume mới.

## Voucher và khuyến mãi

Với database hiện có, xác minh bản sao lưu MySQL rồi chạy `database/mysql/22_vouchers.sql` sau migration `21_user_tracking.sql`. Docker Compose chạy migration theo thứ tự khi tạo volume mới.

ADMIN quản lý mã tại `/admin/vouchers`. BUYER tìm mã công khai trong Ví voucher và áp dụng khi đặt món trước. STAFF quản lý một mã trên mỗi hóa đơn tại màn hình phục vụ. Lượt được giữ khi đơn được lưu hoặc yêu cầu đổi món được duyệt, chốt khi thanh toán và trả lại khi đơn hủy hoặc mã được gỡ hợp lệ. Điều kiện và số tiền giảm trên hóa đơn được lưu theo lần sử dụng.

Mức cọc theo tiền món áp dụng trên giá sau giảm và vẫn giữ mức tối thiểu theo loại bàn. Các thay đổi làm lệch số tiền QR còn hiệu lực hoặc cần đối soát phải chờ xử lý giao dịch. Lượt thanh toán DEMO được ghi nhận riêng trong lịch sử vận hành và được loại khỏi tổng lượt sử dụng thực tế.

- Nút **Quyền riêng tư** cho phép khách bật hoặc tắt phân tích. Phiên dùng một cookie ngẫu nhiên có chữ ký, hết hạn sau 90 ngày. Khi đăng nhập BUYER, phiên đang hoạt động được liên kết với ID tài khoản.
- Một lượt truy cập là một lượt xem trang. Phiên mới bắt đầu sau 30 phút không hoạt động. Thời gian hoạt động chỉ cộng khi trang hiển thị, có focus và còn tương tác gần đây.
- Từ khóa được giới hạn ở các cụm khớp tên món hoặc danh mục đang bán. Báo cáo dùng tên trang chuẩn hóa, ID món và ID yêu cầu đặt bàn nội bộ. Nội dung trò chuyện, biểu mẫu cá nhân và dữ liệu chuyển khoản nằm ngoài dữ liệu tracking.
- Phễu chính đếm theo phiên có lượt xem, với đối tượng xác định tại lúc bắt đầu đặt bàn. Hai nhánh cọc đếm theo yêu cầu đã gửi và dùng trạng thái đặt bàn, cọc từ MySQL. Lượt bỏ dở là lượt bắt đầu nhưng chưa gửi sau 30 phút không hoạt động. Yêu cầu đã gửi và đang chờ vẫn thuộc nhóm đã gửi.
- Tác vụ lúc 03:35 theo giờ Việt Nam dọn dữ liệu tracking quá 90 ngày. Dùng `TRACKING_ENABLED=false` ở môi trường kiểm thử và khi chạy tự động hóa trên dữ liệu dùng chung. Dữ liệu tracking từ các phiên SMS local cũ và yêu cầu đặt bàn có khoản cọc DEMO được loại khỏi thống kê tương ứng.
