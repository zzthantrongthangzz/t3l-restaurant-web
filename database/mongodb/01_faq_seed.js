// Chạy trên máy host: mongosh "mongodb://localhost:27018/t3l_restaurant" database/mongodb/01_faq_seed.js
db = db.getSiblingDB("t3l_restaurant");
db.faqs.createIndex({ published: 1, displayOrder: 1 });
db.faqs.updateOne(
  { _id: "reservation-policy" },
  { $set: { question: "Tôi cần đặt bàn trước bao lâu?", answer: "Bạn có thể gửi yêu cầu đặt bàn trực tuyến. Yêu cầu chỉ được xác nhận sau khi nhân viên nhà hàng liên hệ lại.", displayOrder: 1, published: true } },
  { upsert: true }
);
db.faqs.updateOne(
  { _id: "change-reservation" },
  { $set: { question: "Tôi có thể thay đổi số khách sau khi đặt không?", answer: "Vui lòng gọi nhà hàng và cung cấp mã đặt bàn để nhân viên cập nhật yêu cầu.", displayOrder: 2, published: true } },
  { upsert: true }
);
db.faqs.updateOne(
  { _id: "opening-time" },
  { $set: { question: "Nhà hàng mở cửa khi nào?", answer: "Nhà hàng phục vụ mỗi ngày từ 10:00 đến 22:00.", displayOrder: 3, published: true } },
  { upsert: true }
);
