-- MySQL 8. Chạy khi tạo database mới; script có thể chạy lại để nạp thêm dữ liệu mẫu.
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS t3l_restaurant CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE t3l_restaurant;

CREATE TABLE IF NOT EXISTS dishes (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(140) NOT NULL,
  description VARCHAR(1000) NOT NULL,
  price DECIMAL(12,0) NOT NULL,
  image_url VARCHAR(500),
  image_public_id VARCHAR(255),
  dietary_label VARCHAR(80),
  category_name VARCHAR(80) NOT NULL,
  available BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE KEY uk_dishes_name (name)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS restaurant_tables (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(32) NOT NULL UNIQUE,
  table_type VARCHAR(40) NOT NULL,
  capacity INT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS reservations (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(20) NOT NULL UNIQUE,
  guest_name VARCHAR(120) NOT NULL,
  guest_phone VARCHAR(30) NOT NULL,
  guest_email VARCHAR(180),
  table_id BIGINT,
  reservation_date DATE NOT NULL,
  reservation_time TIME NOT NULL,
  guest_count INT NOT NULL,
  note VARCHAR(500),
  consent_accepted BOOLEAN NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING_CONFIRMATION',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_reservations_table FOREIGN KEY (table_id) REFERENCES restaurant_tables(id),
  INDEX idx_reservations_schedule (reservation_date, status),
  INDEX idx_reservations_table_date (table_id, reservation_date, status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS app_users (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(80) NOT NULL UNIQUE,
  password VARCHAR(100) NOT NULL,
  role VARCHAR(30) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS staff_shifts (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  employee_id BIGINT NOT NULL,
  opened_at DATETIME NOT NULL,
  closed_at DATETIME,
  opening_cash DECIMAL(14,0) NOT NULL DEFAULT 0,
  cash_sales DECIMAL(14,0),
  other_sales DECIMAL(14,0),
  counted_cash DECIMAL(14,0),
  cash_difference DECIMAL(14,0),
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  CONSTRAINT fk_staff_shifts_employee FOREIGN KEY (employee_id) REFERENCES app_users(id),
  INDEX idx_staff_shifts_employee_status (employee_id, status),
  INDEX idx_staff_shifts_opened (opened_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS cash_sales (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  shift_id BIGINT NOT NULL,
  dish_id BIGINT NOT NULL,
  dish_name VARCHAR(140) NOT NULL,
  quantity INT NOT NULL,
  unit_price DECIMAL(12,0) NOT NULL,
  total_amount DECIMAL(14,0) NOT NULL,
  payment_method VARCHAR(20) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_cash_sales_shift FOREIGN KEY (shift_id) REFERENCES staff_shifts(id),
  CONSTRAINT fk_cash_sales_dish FOREIGN KEY (dish_id) REFERENCES dishes(id),
  INDEX idx_cash_sales_shift_method (shift_id, payment_method),
  INDEX idx_cash_sales_created_at (created_at)
) ENGINE=InnoDB;

INSERT INTO restaurant_tables(code, table_type, capacity, active)
VALUES
  ('VIP-01','Phòng VIP',8,TRUE),('VIP-02','Phòng VIP',8,TRUE),
  ('VIP-03','Phòng VIP',6,TRUE),('VIP-04','Phòng VIP',6,TRUE),
  ('Bàn 005','Bàn nhỏ',2,TRUE),('Bàn 006','Bàn nhỏ',2,TRUE),
  ('Bàn 007','Bàn nhỏ',2,TRUE),('Bàn 008','Bàn nhỏ',4,TRUE),
  ('Bàn 009','Bàn nhỏ',4,TRUE),('Bàn 010','Bàn nhỏ',4,TRUE),
  ('Bàn 011','Bàn nhỏ',4,TRUE),('Bàn 012','Bàn nhỏ',4,TRUE),
  ('Bàn 013','Bàn nhỏ',4,TRUE),('Bàn 014','Bàn nhỏ',6,TRUE),
  ('Bàn 015','Bàn nhỏ',6,TRUE),('Bàn 016','Bàn nhỏ',6,TRUE),
  ('Bàn 017','Bàn nhỏ',6,TRUE),('Bàn 018','Bàn nhỏ',6,TRUE)
ON DUPLICATE KEY UPDATE table_type=VALUES(table_type), capacity=VALUES(capacity), active=TRUE;

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
ON DUPLICATE KEY UPDATE description=VALUES(description), price=VALUES(price), category_name=VALUES(category_name), dietary_label=VALUES(dietary_label), available=VALUES(available);

-- Dữ liệu đặt bàn mẫu cho hôm nay/ngày mai. Không ghi tên khách lên giao diện công khai.
INSERT INTO reservations(code,guest_name,guest_phone,guest_email,table_id,reservation_date,reservation_time,guest_count,note,consent_accepted,status)
SELECT 'T3L-SAMPLE-001','Nguyễn Minh Anh','0901000001','minhanh@example.com',t.id,CURRENT_DATE,'18:30:00',4,'Dữ liệu minh họa đặt bàn',TRUE,'CONFIRMED'
FROM restaurant_tables t WHERE t.code='Bàn 008'
AND NOT EXISTS (SELECT 1 FROM reservations r WHERE r.code='T3L-SAMPLE-001');

INSERT INTO reservations(code,guest_name,guest_phone,guest_email,table_id,reservation_date,reservation_time,guest_count,note,consent_accepted,status)
SELECT 'T3L-SAMPLE-002','Trần Gia Bảo','0901000002','giabao@example.com',t.id,CURRENT_DATE,'19:00:00',2,'Dữ liệu minh họa đặt bàn',TRUE,'PENDING_CONFIRMATION'
FROM restaurant_tables t WHERE t.code='Bàn 006'
AND NOT EXISTS (SELECT 1 FROM reservations r WHERE r.code='T3L-SAMPLE-002');

INSERT INTO reservations(code,guest_name,guest_phone,guest_email,table_id,reservation_date,reservation_time,guest_count,note,consent_accepted,status)
SELECT 'T3L-SAMPLE-003','Lê Thu Hà','0901000003','thuha@example.com',t.id,DATE_ADD(CURRENT_DATE, INTERVAL 1 DAY),'12:00:00',6,'Dữ liệu minh họa đặt bàn',TRUE,'CONFIRMED'
FROM restaurant_tables t WHERE t.code='VIP-01'
AND NOT EXISTS (SELECT 1 FROM reservations r WHERE r.code='T3L-SAMPLE-003');
