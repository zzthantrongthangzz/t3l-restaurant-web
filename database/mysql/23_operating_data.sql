-- Dữ liệu giả định cho môi trường thực hành. Không tạo bàn ngoài danh mục.
USE t3l_restaurant;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

DROP PROCEDURE IF EXISTS t3l_seed_operations;
DELIMITER //
CREATE PROCEDURE t3l_seed_operations()
seed: BEGIN
  DECLARE local_now DATETIME DEFAULT (UTC_TIMESTAMP() + INTERVAL 7 HOUR);
  DECLARE duration INT DEFAULT 120;
  DECLARE next_slot DATETIME;
  DECLARE service_shift BIGINT;
  DECLARE an_id BIGINT;
  DECLARE ha_id BIGINT;
  DECLARE order_anchor CHAR(20) DEFAULT 'T3L-8C4F74D6B9472A10';
  DECLARE history_table BIGINT;
  DECLARE squid BIGINT;
  DECLARE shrimp BIGINT;
  DECLARE salad BIGINT;
  DECLARE rice BIGINT;
  DECLARE tea BIGINT;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    DROP TEMPORARY TABLE IF EXISTS t3l_operating_plan, t3l_paid_orders;
    RESIGNAL;
  END;

  IF EXISTS (SELECT 1 FROM reservations WHERE code = order_anchor) THEN
    LEAVE seed;
  END IF;

  SET duration = COALESCE((SELECT duration_minutes FROM restaurant_settings WHERE id=1),120);
  SET next_slot = TIMESTAMP(DATE(local_now), '18:00:00');
  IF local_now + INTERVAL duration MINUTE > next_slot THEN
    SET next_slot = next_slot + INTERVAL 1 DAY;
  END IF;
  SET history_table = (SELECT id FROM restaurant_tables WHERE code='Bàn 018');
  SET squid = (SELECT id FROM dishes WHERE name='Chả mực Hạ Long');
  SET shrimp = (SELECT id FROM dishes WHERE name='Chạo tôm mía');
  SET salad = (SELECT id FROM dishes WHERE name='Gỏi bò bóp thấu');
  SET rice = (SELECT id FROM dishes WHERE name='Cơm sen ngũ sắc');
  SET tea = (SELECT id FROM dishes WHERE name='Trà đào cam sả');
  IF history_table IS NULL OR squid IS NULL OR shrimp IS NULL OR salad IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Cần nạp danh mục bàn và món trước khi nạp dữ liệu vận hành.';
  END IF;

  START TRANSACTION;
  INSERT INTO app_users(username,password,role,enabled,full_name,email,phone) VALUES
    ('nguyen.hoang.an',CONCAT('!',UUID()),'STAFF',TRUE,'Nguyễn Hoàng An','nguyen.hoang.an@example.test','0900000011'),
    ('tran.minh.binh',CONCAT('!',UUID()),'STAFF',TRUE,'Trần Minh Bình','tran.minh.binh@example.test','0900000012'),
    ('pham.thu.ha',CONCAT('!',UUID()),'STAFF',TRUE,'Phạm Thu Hà','pham.thu.ha@example.test','0900000013'),
    ('vo.quoc.huy',CONCAT('!',UUID()),'STAFF',TRUE,'Võ Quốc Huy','vo.quoc.huy@example.test','0900000017'),
    ('local-sms:0900000014',CONCAT('!',UUID()),'BUYER',TRUE,'Trần Ngọc Mai','tran.ngoc.mai@example.test','0900000014'),
    ('desk:0900000021',CONCAT('!',UUID()),'BUYER',TRUE,'Nguyễn Gia Hân','nguyen.gia.han@example.test','0900000021'),
    ('desk:0900000022',CONCAT('!',UUID()),'BUYER',TRUE,'Phạm Quốc Bảo','pham.quoc.bao@example.test','0900000022'),
    ('desk:0900000023',CONCAT('!',UUID()),'BUYER',TRUE,'Vũ Minh Châu','vu.minh.chau@example.test','0900000023'),
    ('desk:0900000024',CONCAT('!',UUID()),'BUYER',TRUE,'Lê Thanh Tâm','le.thanh.tam@example.test','0900000024'),
    ('desk:0900000025',CONCAT('!',UUID()),'BUYER',TRUE,'Đỗ Hoàng Nam','do.hoang.nam@example.test','0900000025'),
    ('desk:0900000026',CONCAT('!',UUID()),'BUYER',TRUE,'Hồ Thiên An','ho.thien.an@example.test','0900000026')
  ON DUPLICATE KEY UPDATE id=app_users.id;

  SET an_id=(SELECT id FROM app_users WHERE username='nguyen.hoang.an' AND role='STAFF');
  SET ha_id=(SELECT id FROM app_users WHERE username='pham.thu.ha' AND role='STAFF');
  SET service_shift=(SELECT id FROM staff_shifts WHERE employee_id=an_id AND status='OPEN' ORDER BY opened_at DESC LIMIT 1);
  IF service_shift IS NULL THEN
    INSERT INTO staff_shifts(employee_id,opened_at,opening_cash,status)
    VALUES (an_id,local_now - INTERVAL 50 MINUTE,500000,'OPEN');
    SET service_shift=LAST_INSERT_ID();
  END IF;

  CREATE TEMPORARY TABLE t3l_operating_plan (
    code VARCHAR(24) PRIMARY KEY, table_code VARCHAR(40), customer VARCHAR(80),
    start_at DATETIME, status VARCHAR(32), guests INT, note VARCHAR(500)
  ) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
  INSERT INTO t3l_operating_plan VALUES
    ('T3L-4F37A1B680E9C522','Bàn 008','desk:0900000021',local_now - INTERVAL 12 MINUTE,'SEATED',3,'Khách muốn ngồi gần cửa sổ'),
    ('T3L-937DEA2F618B5E44','Bàn 017','desk:0900000022',local_now - INTERVAL 25 MINUTE,'SEATED',5,NULL),
    ('T3L-56C0FF1A2E73BB19','Bàn 008','local-sms:0900000014',next_slot,'CONFIRMED',4,'Kỷ niệm sinh nhật'),
    ('T3L-76AB20FC795D2741','Bàn 009','desk:0900000023',next_slot,'PENDING_CONFIRMATION',2,'Gọi xác nhận sau 14 giờ'),
    ('T3L-9AF02ED64163CD76','Bàn 010','desk:0900000024',next_slot + INTERVAL 30 MINUTE,'CONFIRMED',3,'Dọn món khai vị trước'),
    ('T3L-D8E2C31F597E6047','Bàn 011','desk:0900000025',next_slot + INTERVAL 1 DAY,'CONFIRMED',4,NULL),
    ('T3L-0D27C5E9B63A184F','Bàn 017','desk:0900000026',next_slot + INTERVAL 1 HOUR,'PENDING_CONFIRMATION',6,'Cần thêm ghế trẻ em');

  INSERT INTO reservations(code,guest_name,guest_phone,guest_email,table_id,customer_id,reservation_date,
    reservation_time,ends_at,guest_count,note,consent_accepted,status,created_at,checked_in_at,seated_at,service_shift_id,
    deposit_amount,deposit_status,deposit_due_at)
  SELECT p.code,u.full_name,u.phone,u.email,t.id,u.id,DATE(p.start_at),TIME(p.start_at),p.start_at + INTERVAL duration MINUTE,
    p.guests,p.note,TRUE,p.status,IF(p.status='SEATED',p.start_at - INTERVAL 1 MINUTE,local_now),
    IF(p.status='SEATED',p.start_at,NULL),IF(p.status='SEATED',p.start_at,NULL),IF(p.status='SEATED',service_shift,NULL),
    IF(p.code='T3L-9AF02ED64163CD76',CEIL(d.price*2*0.70),0),
    IF(p.code='T3L-9AF02ED64163CD76','WAITING_PAYMENT','NOT_REQUIRED'),
    IF(p.code='T3L-9AF02ED64163CD76',LEAST(local_now + INTERVAL 2 HOUR,p.start_at),NULL)
  FROM t3l_operating_plan p
  JOIN restaurant_tables t ON t.code=p.table_code AND t.active=TRUE AND t.capacity>=p.guests
  JOIN app_users u ON u.username=p.customer AND u.role='BUYER'
  JOIN dishes d ON d.id=shrimp
  WHERE NOT EXISTS (
    SELECT 1 FROM reservations r WHERE r.table_id=t.id
      AND r.status IN ('PENDING_CONFIRMATION','CONFIRMED','CHECKED_IN','SEATED')
      AND ((p.status='SEATED' AND r.status IN ('CHECKED_IN','SEATED'))
        OR (TIMESTAMP(r.reservation_date,r.reservation_time) < p.start_at + INTERVAL duration MINUTE
          AND COALESCE(r.ends_at,TIMESTAMP(r.reservation_date,r.reservation_time) + INTERVAL duration MINUTE)>p.start_at))
  );

  INSERT INTO dining_orders(reservation_id,status,opened_by,opened_at)
  SELECT id,'DRAFT','nguyen.hoang.an',created_at FROM reservations
  WHERE code IN ('T3L-4F37A1B680E9C522','T3L-937DEA2F618B5E44','T3L-9AF02ED64163CD76');
  INSERT INTO dining_order_items(order_id,dish_id,dish_name,unit_price,quantity,note,preordered)
  SELECT o.id,d.id,d.name,d.price,2,IF(r.code='T3L-4F37A1B680E9C522','Không hành',NULL),r.code='T3L-9AF02ED64163CD76'
  FROM dining_orders o JOIN reservations r ON r.id=o.reservation_id
  JOIN dishes d ON d.id=IF(r.code='T3L-937DEA2F618B5E44',salad,shrimp)
  WHERE r.code IN ('T3L-4F37A1B680E9C522','T3L-937DEA2F618B5E44','T3L-9AF02ED64163CD76');

  CREATE TEMPORARY TABLE t3l_paid_orders (
    n INT PRIMARY KEY, code CHAR(20), paid_at DATETIME, shift_id BIGINT, method VARCHAR(20)
  ) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
  INSERT INTO t3l_paid_orders(n,code,paid_at,method)
  SELECT n,IF(n=1,order_anchor,CONCAT('T3L-',LEFT(UPPER(SHA2(CONCAT('t3l-paid-order:',n),256)),16))),
    TIMESTAMP(DATE(local_now) - INTERVAL n DAY,IF(MOD(n,3)=0,'13:20:00','19:30:00')),
    IF(MOD(n,2)=0,'CASH','TRANSFER')
  FROM (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 5 UNION ALL SELECT 7 UNION ALL
        SELECT 9 UNION ALL SELECT 13 UNION ALL SELECT 21 UNION ALL SELECT 27 UNION ALL SELECT 35 UNION ALL
        SELECT 60 UNION ALL SELECT 90 UNION ALL SELECT 180 UNION ALL SELECT 310) offsets;

  INSERT INTO staff_shifts(employee_id,opened_at,closed_at,opening_cash,status)
  SELECT ha_id,paid_at - INTERVAL 4 HOUR,paid_at + INTERVAL 5 MINUTE,300000,'CLOSED' FROM t3l_paid_orders;
  UPDATE t3l_paid_orders p JOIN staff_shifts sh ON sh.employee_id=ha_id AND sh.opened_at=p.paid_at - INTERVAL 4 HOUR
  SET p.shift_id=sh.id;
  INSERT INTO reservations(code,guest_name,guest_phone,guest_email,table_id,customer_id,reservation_date,reservation_time,
    ends_at,guest_count,consent_accepted,status,created_at,checked_in_at,seated_at,completed_at,service_shift_id)
  SELECT p.code,u.full_name,u.phone,u.email,history_table,u.id,DATE(p.paid_at),TIME(p.paid_at - INTERVAL 80 MINUTE),
    p.paid_at,2 + MOD(p.n,4),TRUE,'COMPLETED',p.paid_at - INTERVAL 1 DAY,
    p.paid_at - INTERVAL 80 MINUTE,p.paid_at - INTERVAL 80 MINUTE,p.paid_at,p.shift_id
  FROM t3l_paid_orders p JOIN app_users u ON u.username=ELT(1+MOD(p.n,4),
    'desk:0900000021','desk:0900000022','desk:0900000023','desk:0900000024');
  INSERT INTO dining_orders(reservation_id,status,opened_by,opened_at,finalized_at)
  SELECT r.id,'PAID','pham.thu.ha',r.seated_at,r.completed_at FROM t3l_paid_orders p JOIN reservations r ON r.code=p.code;
  INSERT INTO dining_order_items(order_id,dish_id,dish_name,unit_price,quantity)
  SELECT o.id,d.id,d.name,d.price,1 + MOD(p.n+d.id,4)
  FROM t3l_paid_orders p JOIN reservations r ON r.code=p.code JOIN dining_orders o ON o.reservation_id=r.id
  JOIN dishes d ON d.id IN (squid, shrimp, IF(MOD(p.n,2)=0,salad,COALESCE(rice,salad)), COALESCE(tea,shrimp));
  UPDATE dining_orders o JOIN reservations r ON r.id=o.reservation_id JOIN t3l_paid_orders p ON p.code=r.code
  JOIN (SELECT order_id,SUM(unit_price*quantity) amount FROM dining_order_items GROUP BY order_id) i ON i.order_id=o.id
  SET o.total_amount=i.amount;
  INSERT INTO dining_payments(order_id,shift_id,amount,payment_method,transfer_reference,received_by,paid_at)
  SELECT o.id,p.shift_id,o.total_amount,p.method,IF(p.method='TRANSFER',CONCAT('FT',LEFT(SUBSTRING(p.code,5),12)),NULL),'pham.thu.ha',p.paid_at
  FROM t3l_paid_orders p JOIN reservations r ON r.code=p.code JOIN dining_orders o ON o.reservation_id=r.id;
  INSERT INTO payment_reference_claims(reference_code,purpose,record_id)
  SELECT x.transfer_reference,'INVOICE',x.order_id FROM t3l_paid_orders p JOIN dining_payments x ON x.shift_id=p.shift_id
  WHERE x.transfer_reference IS NOT NULL;
  UPDATE staff_shifts sh JOIN t3l_paid_orders p ON p.shift_id=sh.id JOIN dining_payments x ON x.shift_id=sh.id
  SET sh.cash_sales=IF(x.payment_method='CASH',x.amount,0),sh.other_sales=IF(x.payment_method='TRANSFER',x.amount,0),
    sh.counted_cash=sh.opening_cash+IF(x.payment_method='CASH',x.amount,0),sh.cash_difference=0;

  INSERT INTO reservation_events(reservation_id,actor,description,created_at)
  SELECT r.id,'nguyen.hoang.an',IF(p.status='SEATED','Đã nhận khách tại bàn.',
    IF(p.status='CONFIRMED','Đã gọi khách xác nhận lịch đặt.','Tiếp nhận yêu cầu giữ chỗ.')),r.created_at
  FROM t3l_operating_plan p JOIN reservations r ON r.code=p.code;

  DROP TEMPORARY TABLE t3l_operating_plan, t3l_paid_orders;
  COMMIT;
END //
DELIMITER ;

CALL t3l_seed_operations();
DROP PROCEDURE t3l_seed_operations;
