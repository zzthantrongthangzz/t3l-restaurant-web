// Chạy trên máy host: mongosh "mongodb://localhost:27018/t3l_restaurant" database/mongodb/01_faq_seed.js
db = db.getSiblingDB("t3l_restaurant");
db.faqs.createIndex({ published: 1, displayOrder: 1 });
db.faqs.updateOne(
  { _id: "reservation-policy" },
  { $setOnInsert: { question: "Tôi cần đặt bàn trước bao lâu?", answer: "Bạn có thể gửi yêu cầu đặt bàn trực tuyến. Yêu cầu chỉ được xác nhận sau khi nhân viên nhà hàng liên hệ lại.", displayOrder: 1, published: true } },
  { upsert: true }
);
db.faqs.updateOne(
  { _id: "change-reservation" },
  { $setOnInsert: { question: "Tôi có thể thay đổi số khách sau khi đặt không?", answer: "Vui lòng gọi nhà hàng và cung cấp mã đặt bàn để nhân viên cập nhật yêu cầu.", displayOrder: 2, published: true } },
  { upsert: true }
);
db.faqs.updateOne(
  { _id: "opening-time" },
  { $setOnInsert: { question: "Nhà hàng mở cửa khi nào?", answer: "Nhà hàng phục vụ mỗi ngày từ 10:00 đến 22:00.", displayOrder: 3, published: true } },
  { upsert: true }
);

const questions = [
  ["reservation-without-account", "Tôi có thể đặt bàn khi chưa có tài khoản không?",
    "Có. Hãy chọn ngày, giờ, bàn phù hợp rồi nhập họ tên và số điện thoại. Nhân viên sẽ liên hệ để xác nhận giữ chỗ."],
  ["reservation-status", "Tôi xem tình trạng đặt bàn ở đâu?",
    "Mở mục Tra cứu đặt bàn và nhập thông tin theo hướng dẫn. Sau khi đăng nhập, bạn cũng có thể xem các lượt đặt trong tài khoản."],
  ["preorder-eligibility", "Làm sao để đặt món trước khi đến?",
    "Đăng nhập, tạo lượt đặt bàn rồi chọn đặt kèm món. Bạn có thể chọn số lượng và ghi chú cho từng món."],
  ["preorder-deposit", "Khi nào tôi cần thanh toán thêm tiền cọc?",
    "Nếu thêm món vào lượt đặt trước, hệ thống tính lại cọc và trừ số tiền đã nhận. Phần còn thiếu, nếu có, hiển thị trên trang chi tiết lượt đặt."],
  ["late-arrival", "Nếu tới trễ so với giờ đặt, tôi cần làm gì?",
    "Hãy gọi nhà hàng, cung cấp mã đặt bàn và giờ đến dự kiến. Nhân viên sẽ kiểm tra thời gian giữ bàn và hỗ trợ nếu còn bàn phù hợp."],
  ["table-color", "Màu đỏ trên sơ đồ đặt bàn có nghĩa gì?",
    "Bàn không khả dụng trong khung giờ đã chọn. Hãy đổi giờ hoặc chọn một bàn trống phù hợp với số khách."],
  ["dish-allergy", "Tôi có thể ghi chú món không hành hoặc dị ứng không?",
    "Bạn có thể ghi chú cho mỗi món. Với dị ứng thực phẩm, hãy báo thêm cho nhân viên để nhà hàng kiểm tra nguyên liệu và cách chế biến."],
  ["voucher-terms", "Tôi kiểm tra điều kiện voucher ở đâu?",
    "Mở Ví voucher trong tài khoản để xem thời hạn, mức giảm và giá trị đơn tối thiểu. Hãy kiểm tra mức giảm hiển thị trước khi lưu đơn."],
  ["reservation-cancellation", "Tôi có thể yêu cầu hủy bàn hoặc đổi giờ qua web không?",
    "Mở lượt đặt trong tài khoản để gửi yêu cầu khi lượt đặt còn đủ điều kiện. Theo dõi kết quả xử lý và thông tin hoàn cọc trong chi tiết đặt bàn."]
];

db.faqs.bulkWrite(questions.map(([id, question, answer], index) => ({
  updateOne: {
    filter: { _id: id },
    update: { $setOnInsert: { question, answer, displayOrder: index + 4, published: true } },
    upsert: true
  }
})));
