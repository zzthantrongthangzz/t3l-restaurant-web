# Danh mục màn hình và route T3L Restaurant

Commit `aad2b75541019df4750b69ffa29cbc275d84bdbc` trên `main`. Tham số `{…}` là fixture tổng hợp trong cơ sở dữ liệu QA.

## Phân quyền đọc từ Spring Security

| Khu vực | Role | Cách xử lý |
|---|---|---|
| Trang chủ, thực đơn, giới thiệu, không gian, liên hệ, FAQ, đặt bàn, tra cứu, đăng nhập và khôi phục | Công khai | Hóa đơn/xác nhận đặt bàn còn kiểm soát theo quyền sở hữu hoặc phiên được cấp quyền |
| `/account` và `/account/**` | BUYER | Guest chuyển tới `/login`. Staff và Admin nhận 403. |
| `/staff/**` | STAFF, ADMIN | Guest chuyển tới `/staff/login`. Buyer nhận 403. |
| `/admin/**` | ADMIN | Guest chuyển tới `/staff/login`. Buyer và Staff nhận 403. |
| `/api/menu`, `/api/auth/login` | Công khai theo phương thức cấu hình | Các route API được phục vụ qua chuỗi stateless, độc lập với cookie phiên web. |
| `/api/staff/**`, `/api/admin/**` | JWT theo role | Kiểm thử ma trận tách xác thực cookie phiên web khỏi JWT. |
| `/chat/**`, `/tracking/**` | Tùy nhóm | Chat cho Guest, Buyer, Admin. Tracking cho Guest và Buyer. |
| POST web | CSRF | Chuỗi web giữ kiểm tra CSRF. Chuỗi API stateless dùng Bearer JWT. |

## Màn hình theo role

Kết quả trong bảng chỉ áp dụng cho mở màn hình, HTTP, lỗi runtime đã lắng nghe, theme và tràn ngang cấp trang. Hành vi từng nút được ghi riêng. Mỗi màn hình có tối đa tám tổ hợp.

### GUEST

| Màn hình | Route | Các lần chạy |
|---|---|---|
| home | `/` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| space | `/#space` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| menu | `/menu` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| about | `/about` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| contact | `/contact` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| faq | `/faq` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| booking | `/reservations/new` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| lookup | `/reservations/lookup` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| login | `/login` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| register | `/register` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| forgot-password | `/login/forgot-password` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| legacy-recovery | `/login/recover` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| staff-login | `/staff/login` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| booking-unavailable | `/reservations/confirmation` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| missing-booking-draft | `/reservations/new?draft=qa-missing` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |

### BUYER

| Màn hình | Route | Các lần chạy |
|---|---|---|
| home | `/` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| space | `/#space` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| menu | `/menu` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| about | `/about` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| contact | `/contact` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| faq | `/faq` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| booking | `/reservations/new` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| lookup | `/reservations/lookup` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| account | `/account` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| reservations | `/account/reservations` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| orders | `/account/orders` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| vouchers | `/account/vouchers` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| addresses | `/account/addresses` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| new-address | `/account/addresses/new` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| edit-address | `/account/addresses/{addressId}` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| invoices | `/account/invoices` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| invoice-company | `/account/invoices?tab=company` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| preorder | `/account/reservations/{buyerCode}/preorder` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| confirmation | `/reservations/confirmation?code={buyerCode}` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| lookup-detail | `/reservations/lookup?code={buyerCode}` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| restricted-staff | `/staff/dashboard` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| missing-screen | `/qa-audit-not-a-route` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |

### STAFF

| Màn hình | Route | Các lần chạy |
|---|---|---|
| workspace | `/staff` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| dashboard | `/staff/dashboard` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| reservations | `/staff/reservations` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| new-reservation | `/staff/reservations/new` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| reservation-detail | `/staff/reservations/{reservationId}` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| tables | `/staff/tables` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| orders | `/staff/orders` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| order-detail | `/staff/orders/{orderReservationId}` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| shift | `/staff/shift` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| activity | `/staff/activity` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| restricted-admin | `/admin/accounts` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| restricted-buyer | `/account` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |

### ADMIN

| Màn hình | Route | Các lần chạy |
|---|---|---|
| workspace | `/admin` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| dashboard | `/admin/dashboard` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| reservations | `/admin/reservations` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| new-reservation | `/admin/reservations/new` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| reservation-detail | `/admin/reservations/{reservationId}` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| tables | `/admin/tables` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| orders | `/admin/orders` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| order-detail | `/admin/orders/{orderReservationId}` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| shift | `/admin/shift` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| menu-admin | `/admin/dishes` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| new-dish | `/admin/dishes/new` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| edit-dish | `/admin/dishes/{dishId}/edit` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| accounts | `/admin/accounts` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| new-account | `/admin/accounts/new` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| account-detail | `/admin/accounts/{accountId}` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| inventory | `/admin/inventory` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| new-table | `/admin/inventory/new` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| edit-table | `/admin/inventory/{tableId}/edit` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| categories | `/admin/categories` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| vouchers | `/admin/vouchers` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| new-voucher | `/admin/vouchers/new` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| voucher-detail | `/admin/vouchers/{voucherId}` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| faqs | `/admin/faqs` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| new-faq | `/admin/faqs/new` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| edit-faq | `/admin/faqs/{faqId}/edit` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| contacts | `/admin/contacts` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| contact-detail | `/admin/contacts/{contactId}` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| settings | `/admin/settings` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| invoices | `/admin/invoices` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| invoice-detail | `/admin/invoices/{invoiceId}` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| revenue | `/admin/revenue` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| shifts | `/admin/shifts` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| activity | `/admin/activity` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| analytics | `/admin/analytics` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| analytics-invalid-filter | `/admin/analytics?from=qa-invalid` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |
| restricted-buyer | `/account` | PASS 8 · FAIL 0 · BLOCKED 0 · NOT TESTED 0 |

## Trạng thái và màn hình ngoài ma trận

- Luồng đặt bàn 3 bước, OTP, đơn/voucher, modal và đối soát xuất hiện theo dữ liệu. Kiểm tra đã thực hiện nằm trong bảng thao tác của báo cáo chính.
- `error/403`, `error/404` và `error/500` là template lỗi Spring Boot. 500 dựa trên lỗi gặp trong audit, không cưỡng bức lỗi hệ thống. `/admin/invalid` phục vụ dữ liệu đầu vào sai.
- Trang chi tiết sửa địa chỉ, hóa đơn, lượt đặt, món, bàn, voucher, FAQ, liên hệ và tài khoản dùng ID theo fixture. Không coi biến thể cho mọi bản ghi là đã kiểm thử.
- Các endpoint của Spring Security như `/logout` và Google OAuth, endpoint trả JSON, CSV, poll và fragment được kê riêng khỏi màn hình.

## Route controller (179 mục từ 24 lớp)

| Method | Route | Nguồn |
|---|---|---|
| GET | `/` | WebController.java:108 |
| GET | `/about` | ContactController.java:24 |
| GET | `/account` | CustomerAccountController.java:62 |
| POST | `/account` | CustomerAccountController.java:67 |
| GET | `/account/addresses` | CustomerAccountController.java:130 |
| GET | `/account/addresses/{id}` | CustomerAccountController.java:141 |
| POST | `/account/addresses/{id}` | CustomerAccountController.java:148 |
| POST | `/account/addresses/{id}/default` | CustomerAccountController.java:162 |
| POST | `/account/addresses/{id}/delete` | CustomerAccountController.java:169 |
| GET | `/account/addresses/new` | CustomerAccountController.java:135 |
| POST | `/account/addresses/new` | CustomerAccountController.java:148 |
| POST | `/account/google/link` | CustomerAccountController.java:83 |
| GET | `/account/invoices` | CustomerAccountController.java:176 |
| POST | `/account/invoices/company` | CustomerAccountController.java:194 |
| GET | `/account/orders` | CustomerAccountController.java:109 |
| GET | `/account/reservations` | CustomerAccountController.java:94 |
| GET | `/account/reservations/{code}/preorder` | PreorderController.java:43 |
| POST | `/account/reservations/{code}/preorder` | PreorderController.java:51 |
| GET | `/account/vouchers` | CustomerAccountController.java:118 |
| POST | `/account/vouchers/quote` | VoucherQuoteController.java:31 |
| GET | `/admin` | AdminReportingController.java:30 |
| POST | `/admin/{section:areas\|categories}` | AdminManagementController.java:162 |
| GET | `/admin/accounts` | AdminManagementController.java:33 |
| POST | `/admin/accounts` | AdminManagementController.java:70 |
| GET | `/admin/accounts/{id}` | AdminManagementController.java:55 |
| POST | `/admin/accounts/{id}` | AdminManagementController.java:70 |
| POST | `/admin/accounts/{id}/archive` | AdminManagementController.java:98 |
| POST | `/admin/accounts/{id}/availability` | AdminManagementController.java:91 |
| GET | `/admin/accounts/new` | AdminManagementController.java:49 |
| GET | `/admin/activity` | AdminReportingController.java:81 |
| GET | `/admin/analytics` | AdminAnalyticsController.java:22 |
| GET | `/admin/analytics.csv` | AdminAnalyticsController.java:31 |
| GET | `/admin/categories` | AdminManagementController.java:156 |
| GET | `/admin/contacts` | AdminContactController.java:28 |
| GET | `/admin/contacts/{id}` | AdminContactController.java:36 |
| POST | `/admin/contacts/{id}/status` | AdminContactController.java:43 |
| GET | `/admin/dashboard` | AdminReportingController.java:30 |
| GET | `/admin/dishes` | AdminController.java:24 |
| POST | `/admin/dishes` | AdminController.java:41 |
| POST | `/admin/dishes/{id}` | AdminController.java:41 |
| POST | `/admin/dishes/{id}/delete` | AdminController.java:64 |
| GET | `/admin/dishes/{id}/edit` | AdminController.java:35 |
| POST | `/admin/dishes/{id}/state` | AdminController.java:58 |
| GET | `/admin/dishes/new` | AdminController.java:29 |
| GET | `/admin/faqs` | AdminFaqController.java:24 |
| POST | `/admin/faqs` | AdminFaqController.java:36 |
| POST | `/admin/faqs/{id}` | AdminFaqController.java:36 |
| GET | `/admin/faqs/{id}/edit` | AdminFaqController.java:30 |
| POST | `/admin/faqs/{id}/status` | AdminFaqController.java:45 |
| GET | `/admin/faqs/new` | AdminFaqController.java:30 |
| GET | `/admin/inventory` | AdminManagementController.java:110 |
| POST | `/admin/inventory` | AdminManagementController.java:131 |
| POST | `/admin/inventory/{id}` | AdminManagementController.java:131 |
| GET | `/admin/inventory/{id}/edit` | AdminManagementController.java:125 |
| GET | `/admin/inventory/new` | AdminManagementController.java:119 |
| GET | `/admin/invoices` | AdminReportingController.java:37 |
| GET | `/admin/invoices/{id}` | AdminReportingController.java:45 |
| GET | `/admin/notifications/pending-bookings` | StaffController.java:126 |
| GET | `/admin/orders` | StaffController.java:411 |
| GET | `/admin/orders/{id}` | StaffController.java:417 |
| POST | `/admin/orders/{id}/finalize` | StaffController.java:457 |
| POST | `/admin/orders/{id}/items` | StaffController.java:435 |
| POST | `/admin/orders/{id}/items/{itemId}` | StaffController.java:440 |
| POST | `/admin/orders/{id}/open` | StaffController.java:430 |
| POST | `/admin/orders/{id}/pay` | StaffController.java:462 |
| POST | `/admin/orders/{id}/payment-demo` | StaffController.java:480 |
| POST | `/admin/orders/{id}/payment-intent` | StaffController.java:474 |
| GET | `/admin/orders/{id}/payment-status` | StaffController.java:489 |
| POST | `/admin/orders/{id}/voucher` | StaffController.java:451 |
| GET | `/admin/reservations` | StaffController.java:77 |
| POST | `/admin/reservations` | StaffController.java:198 |
| GET | `/admin/reservations/{id}` | StaffController.java:244 |
| POST | `/admin/reservations/{id}/deposit` | StaffController.java:297 |
| POST | `/admin/reservations/{id}/deposit/record` | StaffController.java:313 |
| POST | `/admin/reservations/{id}/deposit/refund` | StaffController.java:306 |
| GET | `/admin/reservations/{id}/payment-status` | StaffController.java:287 |
| POST | `/admin/reservations/{id}/preorder/unavailable` | StaffController.java:320 |
| POST | `/admin/reservations/{id}/reschedule` | StaffController.java:349 |
| POST | `/admin/reservations/{id}/status` | StaffController.java:269 |
| GET | `/admin/reservations/{id}/table` | StaffController.java:338 |
| POST | `/admin/reservations/{id}/table` | StaffController.java:326 |
| POST | `/admin/reservations/customer` | StaffController.java:162 |
| POST | `/admin/reservations/customer/lookup` | StaffController.java:144 |
| GET | `/admin/reservations/list-status` | StaffController.java:118 |
| GET | `/admin/reservations/new` | StaffController.java:134 |
| POST | `/admin/reservations/requests/{id}/review` | ReservationRequestReviewController.java:16 |
| GET | `/admin/revenue` | AdminReportingController.java:54 |
| GET | `/admin/revenue.csv` | AdminReportingController.java:63 |
| GET | `/admin/settings` | AdminManagementController.java:171 |
| POST | `/admin/settings` | AdminManagementController.java:177 |
| GET | `/admin/shift` | StaffController.java:496 |
| POST | `/admin/shift/close` | StaffController.java:534 |
| POST | `/admin/shift/open` | StaffController.java:519 |
| POST | `/admin/shift/sales` | StaffController.java:524 |
| GET | `/admin/shifts` | AdminReportingController.java:73 |
| GET | `/admin/tables` | StaffController.java:358 |
| POST | `/admin/tables/{id}/availability` | StaffController.java:395 |
| GET | `/admin/vouchers` | AdminVoucherController.java:23 |
| POST | `/admin/vouchers` | AdminVoucherController.java:37 |
| GET | `/admin/vouchers/{id}` | AdminVoucherController.java:31 |
| POST | `/admin/vouchers/{id}` | AdminVoucherController.java:37 |
| POST | `/admin/vouchers/{id}/status` | AdminVoucherController.java:54 |
| GET | `/admin/vouchers/new` | AdminVoucherController.java:31 |
| POST | `/api/auth/login` | AuthApiController.java:30 |
| GET | `/api/menu` | MenuApiController.java:23 |
| GET | `/api/staff/reservations` | StaffReservationApiController.java:23 |
| POST | `/chat/booking-draft` | ChatController.java:112 |
| POST | `/chat/messages` | ChatController.java:93 |
| DELETE | `/chat/session` | ChatController.java:135 |
| GET | `/chat/session` | ChatController.java:75 |
| GET | `/contact` | ContactController.java:27 |
| POST | `/contact` | ContactController.java:34 |
| GET | `/faq` | WebController.java:205 |
| GET | `/login` | CustomerLoginController.java:61 |
| GET | `/login/forgot-password` | PasswordResetController.java:36 |
| POST | `/login/forgot-password/request` | PasswordResetController.java:47 |
| POST | `/login/forgot-password/reset` | PasswordResetController.java:78 |
| POST | `/login/forgot-password/verify` | PasswordResetController.java:68 |
| POST | `/login/password` | CustomerLoginController.java:128 |
| GET | `/login/recover` | CustomerLoginController.java:154 |
| POST | `/login/recover` | CustomerLoginController.java:159 |
| GET | `/menu` | WebController.java:127 |
| GET | `/register` | CustomerLoginController.java:67 |
| POST | `/register` | CustomerLoginController.java:75 |
| POST | `/reservations` | WebController.java:317 |
| POST | `/reservations/{code}/deposit-reference` | WebController.java:258 |
| POST | `/reservations/{code}/payment-demo` | WebController.java:282 |
| GET | `/reservations/{code}/payment-status` | WebController.java:273 |
| POST | `/reservations/{code}/preorder` | WebController.java:160 |
| GET | `/reservations/confirmation` | WebController.java:429 |
| GET | `/reservations/lookup` | ReservationLookupController.java:60 |
| POST | `/reservations/lookup` | ReservationLookupController.java:79 |
| POST | `/reservations/lookup/{code}/cancel` | ReservationLookupController.java:116 |
| POST | `/reservations/lookup/{code}/change` | ReservationLookupController.java:94 |
| POST | `/reservations/lookup/{code}/claim` | ReservationLookupController.java:137 |
| GET | `/reservations/new` | WebController.java:211 |
| POST | `/reservations/quote` | VoucherQuoteController.java:49 |
| GET | `/staff` | StaffDashboardController.java:21 |
| GET | `/staff/activity` | EmployeeActivityController.java:22 |
| GET | `/staff/dashboard` | StaffDashboardController.java:21 |
| GET | `/staff/login` | WebController.java:459 |
| GET | `/staff/notifications/pending-bookings` | StaffController.java:126 |
| GET | `/staff/orders` | StaffController.java:411 |
| GET | `/staff/orders/{id}` | StaffController.java:417 |
| POST | `/staff/orders/{id}/finalize` | StaffController.java:457 |
| POST | `/staff/orders/{id}/items` | StaffController.java:435 |
| POST | `/staff/orders/{id}/items/{itemId}` | StaffController.java:440 |
| POST | `/staff/orders/{id}/open` | StaffController.java:430 |
| POST | `/staff/orders/{id}/pay` | StaffController.java:462 |
| POST | `/staff/orders/{id}/payment-demo` | StaffController.java:480 |
| POST | `/staff/orders/{id}/payment-intent` | StaffController.java:474 |
| GET | `/staff/orders/{id}/payment-status` | StaffController.java:489 |
| POST | `/staff/orders/{id}/voucher` | StaffController.java:451 |
| GET | `/staff/reservations` | StaffController.java:77 |
| POST | `/staff/reservations` | StaffController.java:198 |
| GET | `/staff/reservations/{id}` | StaffController.java:244 |
| POST | `/staff/reservations/{id}/deposit` | StaffController.java:297 |
| POST | `/staff/reservations/{id}/deposit/record` | StaffController.java:313 |
| POST | `/staff/reservations/{id}/deposit/refund` | StaffController.java:306 |
| GET | `/staff/reservations/{id}/payment-status` | StaffController.java:287 |
| POST | `/staff/reservations/{id}/preorder/unavailable` | StaffController.java:320 |
| POST | `/staff/reservations/{id}/reschedule` | StaffController.java:349 |
| POST | `/staff/reservations/{id}/status` | StaffController.java:269 |
| GET | `/staff/reservations/{id}/table` | StaffController.java:338 |
| POST | `/staff/reservations/{id}/table` | StaffController.java:326 |
| POST | `/staff/reservations/customer` | StaffController.java:162 |
| POST | `/staff/reservations/customer/lookup` | StaffController.java:144 |
| GET | `/staff/reservations/list-status` | StaffController.java:118 |
| GET | `/staff/reservations/new` | StaffController.java:134 |
| POST | `/staff/reservations/requests/{id}/review` | ReservationRequestReviewController.java:16 |
| GET | `/staff/shift` | StaffController.java:496 |
| POST | `/staff/shift/close` | StaffController.java:534 |
| POST | `/staff/shift/open` | StaffController.java:519 |
| POST | `/staff/shift/sales` | StaffController.java:524 |
| GET | `/staff/tables` | StaffController.java:358 |
| POST | `/staff/tables/{id}/availability` | StaffController.java:395 |
| POST | `/tracking/events` | TrackingController.java:34 |
| GET | `/tracking/preferences` | TrackingController.java:20 |
| POST | `/tracking/preferences` | TrackingController.java:27 |

## Template (80)

| Template | Form | Dialog | Link |
|---|---:|---:|---:|
| `account/fragments/activity.html` | 0 | 0 | 10 |
| `account/fragments/addresses.html` | 3 | 0 | 3 |
| `account/fragments/invoices.html` | 1 | 0 | 2 |
| `account/fragments/navigation.html` | 1 | 0 | 6 |
| `account/fragments/profile.html` | 2 | 0 | 0 |
| `account/fragments/sidebar.html` | 1 | 0 | 0 |
| `account/panel.html` | 0 | 0 | 1 |
| `account/preorder.html` | 1 | 0 | 2 |
| `admin/account-form.html` | 1 | 0 | 1 |
| `admin/accounts.html` | 1 | 0 | 7 |
| `admin/activity.html` | 1 | 0 | 0 |
| `admin/analytics.html` | 1 | 0 | 1 |
| `admin/categories.html` | 0 | 0 | 1 |
| `admin/contact-detail.html` | 1 | 0 | 1 |
| `admin/contacts.html` | 1 | 0 | 3 |
| `admin/dashboard.html` | 0 | 0 | 10 |
| `admin/dish-form.html` | 2 | 1 | 2 |
| `admin/faq-form.html` | 1 | 0 | 1 |
| `admin/faqs.html` | 1 | 0 | 2 |
| `admin/fragments/account-actions.html` | 2 | 2 | 0 |
| `admin/fragments/analytics.html` | 0 | 0 | 0 |
| `admin/fragments/reason-dialog.html` | 0 | 2 | 0 |
| `admin/fragments/report.html` | 1 | 0 | 3 |
| `admin/fragments/sections.html` | 2 | 0 | 0 |
| `admin/fragments/shell.html` | 0 | 0 | 20 |
| `admin/invalid.html` | 0 | 0 | 1 |
| `admin/inventory.html` | 1 | 0 | 3 |
| `admin/invoice.html` | 0 | 0 | 3 |
| `admin/invoices.html` | 1 | 0 | 2 |
| `admin/revenue.html` | 1 | 0 | 1 |
| `admin/settings.html` | 1 | 0 | 0 |
| `admin/shifts.html` | 1 | 0 | 2 |
| `admin/table-form.html` | 1 | 0 | 1 |
| `admin/voucher-form.html` | 1 | 0 | 4 |
| `admin/vouchers.html` | 2 | 0 | 2 |
| `auth/fragments/account-controls.html` | 0 | 0 | 0 |
| `auth/fragments/customer-shell.html` | 0 | 0 | 1 |
| `auth/login.html` | 1 | 0 | 4 |
| `auth/password-reset.html` | 4 | 0 | 2 |
| `auth/recover.html` | 1 | 0 | 1 |
| `auth/register.html` | 1 | 0 | 1 |
| `auth/staff-login.html` | 1 | 0 | 1 |
| `error/403.html` | 0 | 0 | 0 |
| `error/404.html` | 0 | 0 | 0 |
| `error/500.html` | 0 | 0 | 0 |
| `error/fragments/page.html` | 0 | 0 | 3 |
| `mail/reservation.html` | 0 | 0 | 3 |
| `staff/activity.html` | 1 | 0 | 7 |
| `staff/dashboard.html` | 0 | 1 | 13 |
| `staff/fragments/booking-form.html` | 3 | 0 | 2 |
| `staff/fragments/dashboard.html` | 3 | 1 | 4 |
| `staff/fragments/move-table.html` | 1 | 0 | 0 |
| `staff/fragments/reservation-requests.html` | 1 | 0 | 1 |
| `staff/fragments/settlement.html` | 0 | 0 | 0 |
| `staff/fragments/shell.html` | 0 | 0 | 13 |
| `staff/order.html` | 14 | 0 | 3 |
| `staff/orders.html` | 0 | 0 | 2 |
| `staff/reservation-detail.html` | 7 | 1 | 5 |
| `staff/reservation-new.html` | 0 | 0 | 1 |
| `staff/reservations.html` | 5 | 0 | 17 |
| `staff/shift.html` | 5 | 1 | 5 |
| `staff/tables.html` | 5 | 1 | 14 |
| `web/about.html` | 0 | 0 | 8 |
| `web/confirmation.html` | 0 | 0 | 9 |
| `web/contact.html` | 1 | 0 | 7 |
| `web/faq.html` | 0 | 0 | 2 |
| `web/fragments/about-contact.html` | 0 | 0 | 2 |
| `web/fragments/booking-checkout.html` | 1 | 0 | 2 |
| `web/fragments/booking-details.html` | 0 | 0 | 1 |
| `web/fragments/chatbox.html` | 1 | 0 | 0 |
| `web/fragments/deposit.html` | 2 | 0 | 0 |
| `web/fragments/dish-cards.html` | 1 | 1 | 1 |
| `web/fragments/preorder-picker.html` | 0 | 0 | 3 |
| `web/fragments/restaurant-shell.html` | 0 | 0 | 24 |
| `web/fragments/theme.html` | 0 | 0 | 0 |
| `web/home.html` | 0 | 1 | 8 |
| `web/layout.html` | 0 | 0 | 6 |
| `web/menu.html` | 1 | 1 | 6 |
| `web/reservation-form.html` | 0 | 0 | 0 |
| `web/reservation-lookup.html` | 4 | 0 | 5 |
