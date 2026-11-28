# Chạy lại các công cụ QA

Các script chỉ thao tác trên bản QA ở `127.0.0.1:18085`, MySQL ở `23307` và MongoDB ở `27028`. Script mở `localhost:8085` một lần để kiểm tra HTTP trang chủ. Chạy từ checkout `main` đã ghi trong báo cáo.

1. Tạo một project Docker Compose QA riêng từ `docker-compose.yml`. Đổi `container_name` thành `t3l-full-audit-mysql` và `t3l-full-audit-mongodb`, bind MySQL và MongoDB lần lượt tới `127.0.0.1:23307:3306` và `127.0.0.1:27028:27017`. Dùng volume riêng và `--env-file` riêng. Giữ schema mặc định `t3l_restaurant`, vì seed MySQL tham chiếu tên này. Script dự kiến người dùng SQL `t3l_audit` với mật khẩu dùng một lần dành riêng QA như trong mã công cụ. Các cấu hình này không dùng cho hệ thống thật.
2. Cài `@playwright/test` và `mysql2` vào `target/t3l-qa-audit-runtime`. Đặt `PLAYWRIGHT_BROWSERS_PATH` tới thư mục `browsers` bên trong runtime, sau đó dùng CLI Playwright tại đó để cài Chromium. Không đưa cache hoặc trình duyệt vào gói minh chứng.
3. Biên dịch project bằng Maven. Ghi classpath chứa `target/classes` và các dependency vào `target/t3l-qa-audit-runtime/app-classpath.txt`. Tạo file `qa-password.txt` trong runtime với mật khẩu QA ngẫu nhiên tối thiểu 12 ký tự, rồi gán cùng giá trị vào biến môi trường `QA_LOGIN_PASSWORD` trước khi gọi `run-app.ps1`.
4. Khởi động `smtp-sink.cjs` với `QA_OTP_FILE` trỏ tới `target/t3l-qa-audit-runtime/qa-otp.txt`, và chạy `sepay-sink.cjs`. SMTP chỉ nhận email `qa.invalid`, không gửi thư ra ngoài. `run-app.ps1` dùng cổng `18085`, bật SePay DEMO và đọc feed giao dịch rỗng từ mock loopback. QR trong ảnh là ảnh minh họa của Playwright.
5. Chạy `node qa-audit/tools/audit.cjs`. `QA_RESUME=1` tái dùng Buyer và lượt đặt đã tạo trong lần đầu. `QA_ONLY_SCREENS` giới hạn ID màn hình theo danh sách phân cách dấu phẩy. `QA_RUN_FLOWS=0` bỏ qua luồng ghi và `QA_MATRIX=0` bỏ qua ma trận mở trang. Chạy `node qa-audit/tools/check-extra.cjs` sau khi có lượt đặt QA.
6. Chạy bộ Maven trên schema QA riêng. Đặt `SPRING_DATASOURCE_URL` tới schema QA và MongoDB QA qua `SPRING_DATA_MONGODB_URI`. Chỉ định SMTP loopback và tắt SePay. Lưu kết quả tại `target/t3l-qa-audit-runtime/maven-suite.log`. `node qa-audit/tools/report.cjs` tạo danh mục, báo cáo đọc được và trang HTML lọc kết quả.

Chạy `admin-controls.cjs` để kiểm tra một số thao tác quản trị, `recovery-controls.cjs` để kiểm tra giới hạn OTP, `browser-states.cjs` để kiểm tra điều hướng, theme và trạng thái đang xử lý, và `verify-settlement.cjs` để đối chiếu cùng lượt đặt, hóa đơn và ca đã chạy. Mỗi lệnh dùng Node từ thư mục gốc.

Mỗi dòng JSONL mô tả một lần thử. Báo cáo lấy lần mới nhất nếu một ca được chạy lại. HTML dùng dữ liệu đã lọc và liên kết ảnh, không chứa trạng thái phiên trình duyệt hay trace chứa thông tin đăng nhập.
