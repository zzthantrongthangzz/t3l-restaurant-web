# Full Website Audit · T3L Restaurant

**Nhánh** `main` · **Commit** `aad2b75541019df4750b69ffa29cbc275d84bdbc` · **Ngày** 28/11/2026 (Việt Nam)

## Kết quả và giới hạn

- Playwright dùng Chromium để kiểm tra trực tiếp. Ma trận màn hình có **680/680** lần ghi nhận. PASS 680 · FAIL 0 · BLOCKED 0 · NOT TESTED 0. Chưa ghi nhận **0** lần.
- Thao tác và kiểm tra phân quyền có **52** mục đã ghi nhận. PASS 51 · FAIL 1 · BLOCKED 0 · NOT TESTED 0.
- Tệp minh chứng full-page **754** ảnh JPEG. Hồ sơ phiên trình duyệt, mật khẩu, OTP và log SMTP gốc nằm ngoài gói báo cáo.
- Maven trên schema QA riêng **136 ca · PASS 122 · FAIL 13 · NOT TESTED 1 (bộ test bỏ qua)**. [Tóm tắt Maven đã lọc](playwright-report/maven-summary.txt).
- `PASS` xác nhận đúng các bước và điều kiện đã nêu. `FAIL` là lần chạy có sai khác. `BLOCKED` thiếu trạng thái hoặc fixture tiên quyết. `NOT TESTED` là hạng mục chưa chạy.

## Môi trường

- `git ls-remote origin refs/heads/main` xác nhận cùng commit với checkout. Trình duyệt kiểm tra HTTP công khai của dịch vụ có sẵn tại `localhost:8085`.
- Toàn bộ thao tác ghi và ảnh nghiệp vụ dùng bản QA từ cùng checkout ở `127.0.0.1:18085`. MySQL mới ở `23307`, MongoDB mới ở `27028`. Maven dùng schema `t3l_audit_maven` độc lập.
- SMTP chạy loopback và chỉ nhận `qa.invalid`, không relay. SePay dùng DEMO với feed giao dịch rỗng trên loopback. Playwright thay yêu cầu ảnh VietQR bằng ảnh QA. Thanh toán tiền mặt trong báo cáo là ghi thử vào sổ QA.
- Sau khi Staff xác nhận, công cụ chỉnh ngày giờ của đúng lượt đặt tổng hợp trong DB QA sang khung nhận bàn. Các thao tác nhận bàn, thêm món, chốt đơn, xác nhận tiền mặt và đóng ca được thực hiện qua giao diện.
- CSS/HTML và giao diện được đo tại 1920×1080, 1366×768, 768×1024, 390×844 cho cả Light và Dark. Thao tác nghiệp vụ theo ma trận riêng, không lặp giao dịch ở mọi viewport.
- Báo cáo dùng kết quả cuối của mỗi ca. JSONL và thư mục ảnh lỗi có cả các lần chạy trước khi hiệu chỉnh fixture hoặc công cụ kiểm thử.
- [Danh mục màn hình](PAGE_INVENTORY.md) · [Báo cáo Playwright](playwright-report/index.html) · [Kết quả thô đã lọc](playwright-report/results.jsonl).

## Vấn đề quan sát được

Mức độ lỗi sản phẩm đã có chứng cứ **Critical 0 · High 0 · Medium 1 · Low 0**.

| ID | Mức | Route / role | Bước tái hiện | Mong đợi | Thực tế | Minh chứng | Đề xuất |
|---|---|---|---|---|---|---|---|
| QA-01 | Medium | `/reservations/new` / guest | Mở phiên Guest mới, viewport 390×844 Light, giữ nguyên hộp Phân tích trải nghiệm và thử chạm ô liên hệ. | Hộp quyền riêng tư chừa chỗ hoặc có thể thu gọn mà không che trường cần nhập. | Panel nhận hit-test thay cho các trường: guestName. | [Ảnh](screenshots/mobile/guest-booking-390x844-light.jpg) | Hiển thị banner trong luồng bố cục ở màn hình hẹp hoặc tách nó khỏi vùng biểu mẫu với khoảng chừa và nút thu gọn. |

## Thao tác và các ca cần xác minh tiếp

| Role | Ca | Route | Kết quả | Quan sát | Minh chứng |
|---|---|---|---|---|---|
| guest | live-8085-read-only | `/` | PASS | Existing 8085 responded HTTP 200. Further checks run on isolated 18085. | [Ảnh](screenshots/guest/flow-guest-live-8085-read-only.jpg) |
| guest | register | `/register` | PASS | Registered a synthetic account successfully. | [Ảnh](screenshots/guest/flow-guest-register.jpg) |
| guest | reset-password | `/login/forgot-password` | PASS | OTP captured in the local SMTP sink, masked recipient verified, success banner displayed. | [Ảnh](screenshots/guest/flow-guest-reset-password.jpg) |
| buyer | login | `/login` | PASS | Buyer role reached its account screen. | [Ảnh](screenshots/buyer/flow-buyer-login.jpg) |
| buyer | address-form | `/account/addresses/new` | PASS | Synthetic address created and edit route discovered. | [Ảnh](screenshots/buyer/flow-buyer-address-form.jpg) |
| staff | login | `/staff/login` | PASS | staff reached the expected dashboard. | [Ảnh](screenshots/staff/flow-staff-login.jpg) |
| admin | login | `/staff/login` | PASS | admin reached the expected dashboard. | [Ảnh](screenshots/admin/flow-admin-login.jpg) |
| guest | role-matrix | `/account` | PASS | HTTP 302 | — |
| guest | role-matrix | `/staff/dashboard` | PASS | HTTP 302 | — |
| guest | role-matrix | `/admin/accounts` | PASS | HTTP 302 | — |
| guest | role-matrix | `/api/staff/reservations` | PASS | HTTP 401 | — |
| buyer | role-matrix | `/account` | PASS | HTTP 200 | — |
| buyer | role-matrix | `/staff/dashboard` | PASS | HTTP 403 | — |
| buyer | role-matrix | `/admin/accounts` | PASS | HTTP 403 | — |
| buyer | role-matrix | `/api/staff/reservations` | PASS | HTTP 401 | — |
| staff | role-matrix | `/account` | PASS | HTTP 403 | — |
| staff | role-matrix | `/staff/dashboard` | PASS | HTTP 200 | — |
| staff | role-matrix | `/admin/accounts` | PASS | HTTP 403 | — |
| staff | role-matrix | `/api/staff/reservations` | PASS | HTTP 401 | — |
| admin | role-matrix | `/account` | PASS | HTTP 403 | — |
| admin | role-matrix | `/staff/dashboard` | PASS | HTTP 200 | — |
| admin | role-matrix | `/admin/accounts` | PASS | HTTP 200 | — |
| admin | role-matrix | `/api/staff/reservations` | PASS | HTTP 401 | — |
| guest | menu-search-empty | `/menu` | PASS | Unmatched search rendered its empty result. | [Ảnh](screenshots/guest/flow-guest-menu-search-empty.jpg) |
| guest | menu-details-modal | `/menu` | PASS | Opened a dish dialog and dismissed it with Escape. | [Ảnh](screenshots/guest/flow-guest-menu-details-modal.jpg) |
| guest | contact-submit | `/contact` | PASS | Contact submitted only to the isolated database. | [Ảnh](screenshots/guest/flow-guest-contact-submit.jpg) |
| buyer | booking-preorder-confirmation | `/reservations/new` | PASS | Reservation, confirmation and one preordered dish persisted. | [Ảnh](screenshots/buyer/flow-buyer-booking-preorder-confirmation.jpg) |
| staff | confirm-reservation | `/staff/reservations/{id}` | PASS | Reservation status: CONFIRMED | [Ảnh](screenshots/staff/flow-staff-confirm-reservation.jpg) |
| buyer | deposit-demo | `/reservations/lookup?code={buyerCode}` | PASS | Local payment simulation accepted. Reservation: PENDING_CONFIRMATION. | [Ảnh](screenshots/buyer/flow-buyer-deposit-demo.jpg) |
| staff | open-shift | `/staff/shift` | PASS | Synthetic opening cash recorded. | [Ảnh](screenshots/staff/flow-staff-open-shift.jpg) |
| staff | receive-table | `/staff/reservations/{id}` | PASS | Synthetic schedule advanced into the check-in window. UI changed reservation to SEATED. | [Ảnh](screenshots/staff/flow-staff-receive-table.jpg) |
| staff | add-dish-and-finalize | `/staff/orders/{id}` | PASS | Added a dish and finalized the order via the staff UI. | [Ảnh](screenshots/staff/flow-staff-add-dish-and-finalize.jpg) |
| staff | cash-payment | `/staff/orders/{id}` | PASS | The submitted QA cash payment was persisted and visible as PAID. Cash 97700 VND, simulated deposit applied 111300 VND. | [Ảnh](screenshots/staff/flow-staff-cash-payment.jpg) |
| staff | reconcile-shift | `/staff/shift` | PASS | Verified the closed shift linked to the cash payment. Counted 197700 VND and zero difference. | [Ảnh](screenshots/staff/flow-staff-reconcile-shift.jpg) |
| admin | accounts-filter | `/admin/accounts` | PASS | Accounts filters submitted and empty result was displayed. | [Ảnh](screenshots/admin/flow-admin-accounts-filter.jpg) |
| admin | export-csv | `/admin/revenue.csv` | PASS | CSV downloaded from the UI and discarded from the evidence bundle. | [Ảnh](screenshots/admin/flow-admin-export-csv.jpg) |
| staff | booking-list-pagination | `/staff/reservations` | PASS | Navigated to page 2 and back to page 1. | [Ảnh](screenshots/staff/flow-staff-booking-list-pagination.jpg) |
| admin | activity-filter | `/admin/activity` | PASS | Action filter initialized and returned an empty result. | [Ảnh](screenshots/admin/flow-admin-activity-filter.jpg) |
| guest | privacy-over-booking-form | `/reservations/new` | FAIL | Panel nhận hit-test thay cho các trường: guestName. | [Ảnh](screenshots/mobile/guest-booking-390x844-light.jpg) |
| guest | private-receipt-without-session | `/reservations/confirmation?code={buyerCode}` | PASS | HTTP 404 | — |
| buyer | other-buyers-preorder | `/account/reservations/{otherCode}/preorder` | PASS | HTTP 404 | — |
| admin | create-employee | `/admin/accounts/new` | PASS | Synthetic Staff account saved with its expected role. | [Ảnh](screenshots/admin/flow-admin-create-employee.jpg) |
| admin | create-table | `/admin/inventory/new` | PASS | Table type synchronized to four seats and the synthetic table persisted. | [Ảnh](screenshots/admin/flow-admin-create-table.jpg) |
| admin | create-voucher | `/admin/vouchers/new` | PASS | Time-limited QA voucher saved. | [Ảnh](screenshots/admin/flow-admin-create-voucher.jpg) |
| admin | publish-faq | `/admin/faqs/new` | PASS | Published a QA FAQ and observed it on the public screen. | [Ảnh](screenshots/admin/flow-admin-publish-faq.jpg) |
| admin | contact-status | `/admin/contacts/{id}` | PASS | Only the contact submitted by the QA flow was marked resolved. | [Ảnh](screenshots/admin/flow-admin-contact-status.jpg) |
| staff | staff-jwt-api | `/api/staff/reservations` | PASS | HTTP 200 | — |
| guest | reset-generic-response | `/login/forgot-password` | PASS | Registered and unused phones presented the same OTP screen and the same generic email mask. | [Ảnh](screenshots/guest/flow-guest-reset-generic-response.jpg) |
| guest | reset-resend-cooldown | `/login/forgot-password` | PASS | Immediate resend displayed a cooldown error and the local SMTP code remained unchanged. | [Ảnh](screenshots/guest/flow-guest-reset-resend-cooldown.jpg) |
| guest | reset-attempt-limit | `/login/forgot-password` | PASS | Five wrong OTPs blocked the challenge. The correct OTP was then refused on the same challenge. | [Ảnh](screenshots/guest/flow-guest-reset-attempt-limit.jpg) |
| guest | auth-navigation-and-theme | `/login` | PASS | Password visibility, theme toggle, and customer register/reset/login links were exercised on mobile. | [Ảnh](screenshots/guest/flow-guest-auth-navigation-and-theme.jpg) |
| buyer | booking-loading | `/reservations/new` | PASS | Observed the loading label and disabled confirmation after normal submit handlers ran. The browser then withheld this extra POST to preserve the captured state. | [Ảnh](screenshots/buyer/flow-buyer-booking-loading.jpg) |

## Kết quả Maven cần rà soát

- Nhóm CustomerAccount có 8 ca FAIL. Nhiều assertion còn yêu cầu đăng nhập hoặc đăng ký SMS OTP. Bộ khôi phục mật khẩu bằng email, gồm ca hết hạn, đã PASS. [Danh sách assertion](playwright-report/maven-summary.txt).
- Nhóm AdminWorkspace có 3 ca FAIL liên quan chỉnh món, khóa tài khoản nhân viên và đồng bộ thiết lập thực đơn/khu vực. Playwright đã kiểm tra tạo tài khoản và phân quyền theo role. Các assertion đối ứng của ba ca Maven cần kiểm chứng riêng. [Danh sách assertion](playwright-report/maven-summary.txt).
- Hai ca FAIL ở Reservation và ReservationLookup kỳ vọng từ chối khu vực không tồn tại. Kiểm tra hợp đồng xử lý lựa chọn khu vực và đồng bộ lại validation hoặc kỳ vọng test. [Danh sách assertion](playwright-report/maven-summary.txt).
- Ca dùng Atlas Vector Search được bỏ qua vì QA thiếu cấu hình Atlas. Trạng thái **NOT TESTED**.

## Chưa bao phủ

- **NOT TESTED** toàn bộ nút/form CRUD quản trị, tải ảnh Cloudinary, xác thực Google thật, SMS thật, thanh toán chuyển khoản thật, callback SePay thật, cuộc hội thoại Gemini thật và mọi tổ hợp lỗi mạng/tải chậm.
- **NOT TESTED** tất cả nhánh của phân trang, lọc kết hợp, toàn bộ modal ở tám tổ hợp, thao tác bàn nâng cao (chuyển bàn/hủy/khách không đến), mọi tình huống voucher, công nợ, hoàn cọc và hàng loạt yêu cầu thay đổi đặt bàn.
- **NOT TESTED** kiểm tra thủ công từng ảnh để xác nhận màu/contrast/font trong mọi trạng thái. Ma trận ghi theme, font cơ sở, tràn document, request và lỗi JavaScript. Các lần chạy chưa có ảnh minh chứng được ghi trong kết quả chi tiết.

## Đề xuất UI/UX

- Ưu tiên điểm gãy 390 px và 768 px tại các bảng quản trị/nhân viên. Dùng cuộn cục bộ có chỉ báo nếu cần giữ đủ cột, hoặc hiển thị dòng dạng thẻ.
- Với luồng đặt bàn nhiều bước, hiển thị rõ bước đang thực hiện và giữ nút tiếp tục ở vị trí ổn định sau khi dữ liệu báo giá cập nhật.
- Tách lỗi tải tài nguyên bên ngoài thành một chỉ báo riêng để người dùng vẫn phân biệt được nội dung nghiệp vụ và ảnh minh họa tải lỗi.

Các kiểm thử Maven thất bại được liệt kê tại tệp tóm tắt riêng. Một ca test thất bại hoặc selector không khớp cần kiểm chứng thêm trước khi quy kết lỗi sản phẩm.
