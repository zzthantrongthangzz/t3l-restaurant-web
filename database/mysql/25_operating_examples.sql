-- Kịch bản giả định cho voucher, liên hệ, hồ sơ, yêu cầu đặt bàn và phân tích.
USE t3l_restaurant;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

DROP PROCEDURE IF EXISTS t3l_seed_operating_examples;
DELIMITER //
CREATE PROCEDURE t3l_seed_operating_examples()
seed: BEGIN
  DECLARE local_now DATETIME DEFAULT (UTC_TIMESTAMP() + INTERVAL 7 HOUR);
  DECLARE paid_at DATETIME;
  DECLARE future_start DATETIME;
  DECLARE duration INT DEFAULT 120;
  DECLARE buyer_id BIGINT;
  DECLARE employee_id BIGINT;
  DECLARE table_id_value BIGINT;
  DECLARE shrimp_id BIGINT;
  DECLARE drink_id BIGINT;
  DECLARE paid_reservation BIGINT;
  DECLARE waiting_reservation BIGINT;
  DECLARE request_reservation BIGINT;
  DECLARE paid_order BIGINT;
  DECLARE closed_shift BIGINT;
  DECLARE bill_total DECIMAL(16,0);
  DECLARE counter_total DECIMAL(16,0);
  DECLARE discount DECIMAL(16,0);
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    DROP TEMPORARY TABLE IF EXISTS t3l_journeys;
    RESIGNAL;
  END;

  IF EXISTS (SELECT 1 FROM reservations WHERE code='T3L-E7D94105DB2CF638') THEN
    LEAVE seed;
  END IF;
  SET buyer_id=(SELECT id FROM app_users WHERE username='local-sms:0900000014');
  SET employee_id=(SELECT id FROM app_users WHERE username='tran.minh.binh');
  SET table_id_value=(SELECT id FROM restaurant_tables WHERE code='Bàn 018');
  SET shrimp_id=(SELECT id FROM dishes WHERE name='Chạo tôm mía');
  SET drink_id=(SELECT id FROM dishes WHERE name='Coca-Cola');
  IF buyer_id IS NULL OR employee_id IS NULL OR table_id_value IS NULL OR shrimp_id IS NULL OR drink_id IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Cần nạp đủ danh mục và dữ liệu vận hành từ script 23.';
  END IF;

  SET paid_at=TIMESTAMP(DATE(local_now) - INTERVAL 2 DAY,'19:45:00');
  SET future_start=TIMESTAMP(DATE(local_now) + INTERVAL 2 DAY,'18:00:00');
  SET duration=COALESCE((SELECT duration_minutes FROM restaurant_settings WHERE id=1),120);
  START TRANSACTION;
  INSERT INTO vouchers(code,title,discount_type,discount_value,minimum_total,maximum_discount,
    starts_at,ends_at,total_limit,customer_limit,public_offer,enabled) VALUES
    ('HENBAN10','Hẹn bàn cùng T3L','PERCENT',10,350000,100000,local_now - INTERVAL 3 DAY,local_now + INTERVAL 21 DAY,150,2,TRUE,TRUE),
    ('BANBE80','Bữa tối cùng bạn bè','FIXED',80000,700000,NULL,local_now - INTERVAL 14 DAY,local_now + INTERVAL 25 DAY,70,2,TRUE,TRUE),
    ('BUATRUA50','Bữa trưa tiết kiệm','FIXED',50000,400000,NULL,local_now - INTERVAL 2 DAY,local_now + INTERVAL 3 DAY,50,1,TRUE,TRUE),
    ('SUMHOP15','Ưu đãi tiệc sum họp','PERCENT',15,1000000,200000,local_now + INTERVAL 3 DAY,local_now + INTERVAL 30 DAY,80,1,TRUE,TRUE),
    ('THANHVIEN12','Ưu đãi riêng cho thành viên','PERCENT',12,500000,120000,local_now - INTERVAL 1 DAY,local_now + INTERVAL 45 DAY,120,1,FALSE,TRUE),
    ('MONNGON30','Ưu đãi món mới tháng trước','FIXED',30000,250000,NULL,local_now - INTERVAL 30 DAY,local_now - INTERVAL 3 DAY,40,1,TRUE,TRUE)
  ON DUPLICATE KEY UPDATE id=vouchers.id;

  INSERT INTO staff_shifts(employee_id,opened_at,closed_at,opening_cash,status)
  VALUES (employee_id,paid_at - INTERVAL 4 HOUR,paid_at + INTERVAL 15 MINUTE,300000,'CLOSED');
  SET closed_shift=LAST_INSERT_ID();
  INSERT INTO reservations(code,guest_name,guest_phone,guest_email,table_id,customer_id,reservation_date,reservation_time,
    ends_at,guest_count,consent_accepted,status,created_at,checked_in_at,seated_at,completed_at,service_shift_id)
  SELECT 'T3L-E7D94105DB2CF638',full_name,phone,email,table_id_value,id,DATE(paid_at),TIME(paid_at - INTERVAL 80 MINUTE),
    paid_at,6,TRUE,'COMPLETED',paid_at - INTERVAL 1 DAY,paid_at - INTERVAL 80 MINUTE,paid_at - INTERVAL 80 MINUTE,paid_at,closed_shift
  FROM app_users WHERE id=buyer_id;
  SET paid_reservation=LAST_INSERT_ID();
  -- Chỉ dùng cấu hình voucher gốc cho hóa đơn giả định nếu mã chưa được quản trị viên thay đổi.
  SET discount=COALESCE((SELECT discount_value FROM vouchers WHERE code='BANBE80' AND discount_type='FIXED'
    AND discount_value=80000 AND minimum_total=700000 AND paid_at BETWEEN starts_at AND ends_at),0);
  INSERT INTO dining_orders(reservation_id,status,opened_by,opened_at,finalized_at,voucher_code,voucher_discount)
  VALUES (paid_reservation,'PAID','tran.minh.binh',paid_at - INTERVAL 80 MINUTE,paid_at,IF(discount>0,'BANBE80',NULL),discount);
  SET paid_order=LAST_INSERT_ID();
  INSERT INTO dining_order_items(order_id,dish_id,dish_name,unit_price,quantity)
  SELECT paid_order,id,name,price,IF(id=shrimp_id,8,6) FROM dishes WHERE id IN (shrimp_id,drink_id);
  SELECT SUM(unit_price*quantity) - discount INTO bill_total FROM dining_order_items WHERE order_id=paid_order;
  UPDATE dining_orders SET total_amount=bill_total WHERE id=paid_order;
  INSERT INTO voucher_usages(voucher_id,order_id,active_order_id,customer_key,code,discount_type,discount_value,
    minimum_total,maximum_discount,discount_amount,status,held_at,closed_at,demo)
  SELECT id,paid_order,paid_order,CONCAT('buyer:',buyer_id),code,discount_type,discount_value,minimum_total,maximum_discount,
    discount,'USED',paid_at - INTERVAL 20 MINUTE,paid_at,FALSE FROM vouchers WHERE code='BANBE80' AND discount>0;
  INSERT INTO dining_payments(order_id,shift_id,amount,payment_method,received_by,paid_at)
  VALUES (paid_order,closed_shift,bill_total,'CASH','tran.minh.binh',paid_at);
  INSERT INTO cash_sales(shift_id,dish_id,dish_name,quantity,unit_price,total_amount,payment_method,created_at)
  SELECT closed_shift,id,name,3,price,3*price,'CASH',paid_at - INTERVAL 35 MINUTE FROM dishes WHERE id=drink_id;
  SELECT total_amount INTO counter_total FROM cash_sales WHERE shift_id=closed_shift;
  UPDATE staff_shifts SET cash_sales=bill_total+counter_total,other_sales=0,
    counted_cash=opening_cash+bill_total+counter_total,cash_difference=0 WHERE id=closed_shift;

  INSERT INTO customer_profiles(user_id,family_name,given_name,dietary_preferences)
  SELECT id,'Trần Ngọc','Mai','Không hành sống' FROM app_users WHERE id=buyer_id
  ON DUPLICATE KEY UPDATE user_id=customer_profiles.user_id;
  INSERT INTO customer_addresses(user_id,recipient_name,phone,street_address,is_default)
  SELECT id,full_name,phone,'38 đường Hoàng Lan, phường An Phú, TP. Hồ Chí Minh',TRUE
  FROM app_users WHERE id=buyer_id AND NOT EXISTS (SELECT 1 FROM customer_addresses WHERE user_id=buyer_id);

  INSERT INTO contact_messages(name,email,phone,subject,message,status,created_at,updated_at,handled_by,admin_note)
  VALUES
    ('Vũ Minh Châu','vu.minh.chau@example.test','0900000023','Chỗ ngồi cho trẻ em','Nhà hàng có ghế ăn cho bé hai tuổi không?','NEW',local_now,local_now,NULL,NULL),
    ('Đỗ Hoàng Nam','do.hoang.nam@example.test','0900000025','Đặt tiệc nhóm 12 người','Tôi muốn hỏi về bàn và các món phù hợp cho nhóm 12 người.','IN_PROGRESS',
      local_now - INTERVAL 1 DAY,local_now,'admin','Đang kiểm tra sức chứa và gửi thực đơn nhóm.'),
    ('Lê Thanh Tâm','le.thanh.tam@example.test','0900000024','Xuất hóa đơn cho công ty','Tôi cần xuất hóa đơn cho bữa ăn đã thanh toán.','RESOLVED',
      local_now - INTERVAL 4 DAY,local_now - INTERVAL 3 DAY,'admin','Đã tiếp nhận thông tin xuất hóa đơn.');

  INSERT INTO reservations(code,guest_name,guest_phone,guest_email,table_id,customer_id,reservation_date,reservation_time,
    ends_at,guest_count,consent_accepted,status,created_at)
  SELECT 'T3L-25BA63EC91D847F2',u.full_name,u.phone,u.email,table_id_value,u.id,DATE(future_start),TIME(future_start),
    future_start + INTERVAL duration MINUTE,4,TRUE,'CONFIRMED',local_now - INTERVAL 2 HOUR
  FROM app_users u WHERE u.username='desk:0900000025'
    AND NOT EXISTS (SELECT 1 FROM reservations r WHERE r.table_id=table_id_value
      AND r.status IN ('PENDING_CONFIRMATION','CONFIRMED','CHECKED_IN','SEATED')
      AND TIMESTAMP(r.reservation_date,r.reservation_time)<future_start + INTERVAL duration MINUTE
      AND COALESCE(r.ends_at,TIMESTAMP(r.reservation_date,r.reservation_time)+INTERVAL duration MINUTE)>future_start);
  SET request_reservation=(SELECT id FROM reservations WHERE code='T3L-25BA63EC91D847F2');
  INSERT INTO reservation_change_requests(reservation_id,request_type,status,original_date,original_time,original_guests,
    requested_date,requested_time,requested_guests,reason,created_at)
  SELECT id,'CHANGE','PENDING',reservation_date,reservation_time,guest_count,reservation_date,'18:30:00',5,
    'Thêm một người và dời giờ đến 30 phút.',local_now - INTERVAL 15 MINUTE FROM reservations WHERE id=request_reservation;
  INSERT INTO reservation_events(reservation_id,actor,description,created_at)
  VALUES (paid_reservation,'tran.minh.binh','Đã thanh toán hóa đơn.',paid_at);
  INSERT INTO admin_activity(actor,operation,target,occurred_at)
  VALUES ('admin','Xử lý liên hệ','Đặt tiệc nhóm 12 người',local_now),
    ('tran.minh.binh','Xác nhận thanh toán','Bàn 018 · Đã thu tiền mặt',paid_at);

  SET waiting_reservation=(SELECT id FROM reservations WHERE code='T3L-9AF02ED64163CD76');
  CREATE TEMPORARY TABLE t3l_journeys (n INT PRIMARY KEY, sid VARCHAR(36), bid VARCHAR(36), flow VARCHAR(36),
    start_at DATETIME, kind VARCHAR(8), account BIGINT);
  INSERT INTO t3l_journeys
  SELECT n,CONCAT('7c16230b-39c5-4da4-8881-',LPAD(n,12,'0')),CONCAT('bb03f3a6-cb34-40a6-9145-',LPAD(n,12,'0')),
    CONCAT('1b45f0fe-64a6-401d-8397-',LPAD(n,12,'0')),
    CASE n
      WHEN 2 THEN paid_at - INTERVAL 1 DAY - INTERVAL 5 MINUTE
      WHEN 4 THEN COALESCE((SELECT created_at FROM reservations WHERE id=waiting_reservation) - INTERVAL 5 MINUTE,local_now - INTERVAL 1 DAY)
      WHEN 6 THEN local_now - INTERVAL 125 MINUTE
      ELSE local_now - INTERVAL (15 + n*450) MINUTE
    END,
    IF(MOD(n,2)=0,'BUYER','GUEST'),IF(MOD(n,2)=0,buyer_id,NULL)
  FROM (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6
        UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12) steps;

  INSERT INTO tracking_sessions(id,browser_id,user_id,started_at,last_activity_at,ended_at,request_count)
  SELECT sid,bid,account,start_at,start_at + INTERVAL 5 MINUTE,start_at + INTERVAL 6 MINUTE,0 FROM t3l_journeys;
  INSERT INTO tracking_events(session_id,event_key,event_type,occurred_at,actor_kind,page_key,dish_id,term,quantity,
    active_seconds,flow_key,reservation_id,requires_deposit)
  SELECT j.sid,CONCAT('hanh-trinh:',j.n,':',s.step_no),s.kind,j.start_at + INTERVAL (s.step_no*15) SECOND,j.kind,s.page,
    IF(s.kind IN ('DISH_VIEW','DISH_CLICK','CART_ADD','CART_REMOVE','CART_QUANTITY'),IF(MOD(j.n,3)=0,drink_id,shrimp_id),NULL),
    CASE s.kind WHEN 'SEARCH' THEN IF(MOD(j.n,3)=0,'coca-cola','chạo tôm') WHEN 'CATEGORY' THEN 'Khai vị' ELSE NULL END,
    IF(s.kind LIKE 'CART_%',2,NULL),IF(s.kind='ACTIVE',35+j.n*4,NULL),
    IF(s.step_no>=12,j.flow,NULL),NULL,NULL
  FROM t3l_journeys j JOIN (
    SELECT 0 step_no,'PAGE_VIEW' kind,'home' page,1 cadence UNION ALL SELECT 1,'PAGE_VIEW','menu',1
    UNION ALL SELECT 2,'ACTIVE','menu',1 UNION ALL SELECT 3,'DISH_VIEW','menu',1 UNION ALL SELECT 4,'DISH_CLICK','menu',2
    UNION ALL SELECT 5,'SEARCH','menu',2 UNION ALL SELECT 6,'CATEGORY','menu',3 UNION ALL SELECT 7,'CHAT_OPEN','chat',3
    UNION ALL SELECT 8,'CHAT_MESSAGE','chat',4 UNION ALL SELECT 9,'CART_ADD','menu',2 UNION ALL SELECT 10,'CART_QUANTITY','menu',4
    UNION ALL SELECT 11,'CART_REMOVE','menu',6 UNION ALL SELECT 12,'BOOKING_START','booking',1
    UNION ALL SELECT 13,'TABLE_MAP_VIEW','booking',1 UNION ALL SELECT 14,'TABLE_SELECTED','booking',2
    UNION ALL SELECT 15,'PAGE_VIEW','vouchers',4 UNION ALL SELECT 16,'VOUCHER_VIEW','vouchers',4
    UNION ALL SELECT 17,'VOUCHER_QUOTE','vouchers',6
  ) s ON MOD(j.n,s.cadence)=0;
  INSERT INTO tracking_events(session_id,event_key,event_type,occurred_at,actor_kind,page_key,flow_key,reservation_id,requires_deposit)
  SELECT sid,CONCAT('hanh-trinh:',n,':gui'), 'BOOKING_SUBMITTED',start_at + INTERVAL 5 MINUTE,kind,'booking',flow,
    CASE n WHEN 2 THEN paid_reservation WHEN 4 THEN waiting_reservation ELSE request_reservation END,n=4
  FROM t3l_journeys WHERE n=2 OR (n=4 AND waiting_reservation IS NOT NULL) OR (n=6 AND request_reservation IS NOT NULL);
  INSERT INTO tracking_events(session_id,event_key,event_type,occurred_at,actor_kind,page_key,flow_key,reservation_id)
  SELECT sid,CONCAT('hanh-trinh:',n,':coc'),'DEPOSIT_START',start_at + INTERVAL 325 SECOND,kind,'confirmation',flow,waiting_reservation
  FROM t3l_journeys WHERE n=4 AND waiting_reservation IS NOT NULL;

  DROP TEMPORARY TABLE t3l_journeys;
  COMMIT;
END //
DELIMITER ;

CALL t3l_seed_operating_examples();
DROP PROCEDURE t3l_seed_operating_examples;
