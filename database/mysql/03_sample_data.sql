-- Nạp dữ liệu mẫu cho database đã chuyển sang mô hình một nhà hàng.
USE t3l_restaurant;
SET NAMES utf8mb4;

INSERT INTO restaurant_tables(code,table_type,capacity,active) VALUES
  ('VIP-01','Phòng VIP',8,TRUE),('VIP-02','Phòng VIP',8,TRUE),
  ('VIP-03','Phòng VIP',6,TRUE),('VIP-04','Phòng VIP',6,TRUE),
  ('Bàn 005','Bàn nhỏ',2,TRUE),('Bàn 006','Bàn nhỏ',2,TRUE),
  ('Bàn 007','Bàn nhỏ',2,TRUE),('Bàn 008','Bàn nhỏ',4,TRUE),
  ('Bàn 009','Bàn nhỏ',4,TRUE),('Bàn 010','Bàn nhỏ',4,TRUE),
  ('Bàn 011','Bàn nhỏ',4,TRUE),('Bàn 012','Bàn nhỏ',4,TRUE),
  ('Bàn 013','Bàn nhỏ',4,TRUE),('Bàn 014','Bàn nhỏ',6,TRUE),
  ('Bàn 015','Bàn nhỏ',6,TRUE),('Bàn 016','Bàn nhỏ',6,TRUE),
  ('Bàn 017','Bàn nhỏ',6,TRUE),('Bàn 018','Bàn nhỏ',6,TRUE)
ON DUPLICATE KEY UPDATE table_type=VALUES(table_type),capacity=VALUES(capacity),active=TRUE;

INSERT INTO dishes(name,description,price,category_name,dietary_label,available) VALUES
  ('Gỏi cuốn tôm thịt','Tôm tươi, thịt luộc, rau thơm và nước chấm nhà làm.',79000,'Khai vị','',TRUE),
  ('Chả giò hải sản','Chả giò vàng giòn với tôm, mực và sốt mayonnaise.',89000,'Khai vị','',TRUE),
  ('Salad rau mùa','Rau xanh theo mùa, cà chua bi và sốt mè rang.',69000,'Khai vị','Chay',TRUE),
  ('Cơm gà sốt lá chanh','Đùi gà áp chảo thơm, dùng cùng cơm hạt sen.',129000,'Món chính','',TRUE),
  ('Bò lúc lắc khoai tây','Thăn bò mềm áp chảo cùng hành tây và khoai tây.',189000,'Món chính','',TRUE),
  ('Cá hồi áp chảo','Cá hồi áp chảo dùng với rau củ và sốt bơ chanh.',219000,'Món chính','',TRUE),
  ('Mì Ý sốt bò bằm','Mì Ý dùng cùng sốt cà chua và bò bằm nấu chậm.',139000,'Món chính','',TRUE),
  ('Nấm đậu hũ kho tiêu','Đậu hũ non và nấm theo mùa kho tiêu xanh, dùng với cơm nóng.',99000,'Món chay','Chay',TRUE),
  ('Cơm sen ngũ sắc','Cơm hạt sen, rau củ và nấm xào theo mùa.',109000,'Món chay','Chay',TRUE),
  ('Chè sen long nhãn','Hạt sen bùi thanh, long nhãn và nước đường phèn.',59000,'Tráng miệng','',TRUE),
  ('Bánh flan caramel','Bánh flan mềm mịn, dùng kèm caramel nhà làm.',39000,'Tráng miệng','',TRUE),
  ('Trà đào cam sả','Trà đào thơm nhẹ với cam tươi và sả.',49000,'Đồ uống','',TRUE)
ON DUPLICATE KEY UPDATE description=VALUES(description),price=VALUES(price),category_name=VALUES(category_name),dietary_label=VALUES(dietary_label),available=VALUES(available);

-- Nạp lịch đặt, đơn và báo cáo sau khi chạy toàn bộ các script cấu trúc đến 22.
