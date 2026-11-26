-- Dữ liệu vận hành liên kết. Chạy sau 23, 25, 28 và 30.
USE t3l_restaurant;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

DROP PROCEDURE IF EXISTS t3l_seed_workflows;
DELIMITER //
CREATE PROCEDURE t3l_seed_workflows()
BEGIN
  DECLARE local_now DATETIME DEFAULT (UTC_TIMESTAMP() + INTERVAL 7 HOUR);
  DECLARE opening TIME DEFAULT '10:00:00';
  DECLARE closing TIME DEFAULT '22:00:00';
  DECLARE duration INT DEFAULT 120;
  DECLARE slot_minutes INT DEFAULT 30;
  DECLARE slot_start DATETIME;
  DECLARE slot_end DATETIME;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    DROP TEMPORARY TABLE IF EXISTS t3l_seed_accounts, t3l_seed_plan, t3l_seed_scope, t3l_seed_events;
    RESIGNAL;
  END;

  SELECT COALESCE(MAX(s.opening_time), opening), COALESCE(MAX(s.closing_time), closing),
    COALESCE(MAX(s.duration_minutes), duration), COALESCE(MAX(s.slot_minutes), slot_minutes)
    INTO opening, closing, duration, slot_minutes FROM restaurant_settings s WHERE s.id=1;
  IF slot_minutes <= 0 OR duration <= 0 OR TIME_TO_SEC(TIMEDIFF(closing, opening)) < 60 * duration THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Khung gio dat ban khong hop le. Hay kiem tra cau hinh nha hang.';
  END IF;
  SET slot_start=TIMESTAMP(DATE(local_now), opening);
  WHILE slot_start <= local_now DO
    SET slot_start=slot_start + INTERVAL slot_minutes MINUTE;
  END WHILE;
  IF slot_start + INTERVAL duration MINUTE > TIMESTAMP(DATE(local_now), closing) THEN
    SET slot_start=TIMESTAMP(DATE(local_now) + INTERVAL 1 DAY, opening);
  END IF;
  SET slot_end=slot_start + INTERVAL duration MINUTE;

  START TRANSACTION;
  UPDATE app_users SET full_name='Admin' WHERE username='admin' AND role='ADMIN'
    AND (full_name IS NULL OR TRIM(full_name) IN ('', 'Nguyễn Minh Quân', 'Restaurant Administrator'));

  CREATE TEMPORARY TABLE t3l_seed_accounts AS
  SELECT id, email AS previous_address, CONCAT('t3l.886413+', SUBSTRING_INDEX(email,'@',1), '@gmail.com') AS next_address
  FROM app_users WHERE username IN ('nguyen.hoang.an','tran.minh.binh','pham.thu.ha','vo.quoc.huy',
    'local-sms:0900000014','desk:0900000021','desk:0900000022','desk:0900000023','desk:0900000024','desk:0900000025','desk:0900000026')
    AND email LIKE '%@example.test';
  UPDATE app_users u JOIN t3l_seed_accounts a ON a.id=u.id SET u.email=a.next_address;
  UPDATE reservations r JOIN t3l_seed_accounts a ON a.id=r.customer_id AND r.guest_email=a.previous_address
  SET r.guest_email=a.next_address;
  UPDATE contact_messages c JOIN t3l_seed_accounts a ON c.email COLLATE utf8mb4_0900_ai_ci=a.previous_address
  SET c.email=a.next_address;
  UPDATE tracking_sessions s JOIN tracking_events e ON e.session_id=s.id
  JOIN reservations r ON r.id=e.reservation_id
  SET s.user_id=r.customer_id
  WHERE e.event_key IN ('hanh-trinh:4:gui','hanh-trinh:6:gui') AND r.customer_id IS NOT NULL
    AND NOT (s.user_id <=> r.customer_id);

  CREATE TEMPORARY TABLE t3l_seed_plan (
    code CHAR(20) PRIMARY KEY, table_code VARCHAR(32), user_key VARCHAR(80), guests INT,
    booking_status VARCHAR(24), with_food BOOLEAN, new_booking BOOLEAN NOT NULL DEFAULT TRUE
  ) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
  -- Mỗi ngày có một bộ mã ổn định. Chạy lại giữ nguyên mọi thay đổi đối với các lượt đã tạo.
  INSERT INTO t3l_seed_plan(code,table_code,user_key,guests,booking_status,with_food) VALUES
    (CONCAT('T3L-',UPPER(LEFT(SHA2(CONCAT('t3l-guest-hold/',DATE(local_now)),256),16))), 'Bàn 024','desk:0900000023',3,'PENDING_CONFIRMATION',FALSE),
    (CONCAT('T3L-',UPPER(LEFT(SHA2(CONCAT('t3l-ready-hold/',DATE(local_now)),256),16))), 'Bàn 013','desk:0900000025',3,'CONFIRMED',FALSE),
    (CONCAT('T3L-',UPPER(LEFT(SHA2(CONCAT('t3l-preorder-hold/',DATE(local_now)),256),16))), 'Bàn 023','desk:0900000024',12,'PENDING_CONFIRMATION',TRUE);
  UPDATE t3l_seed_plan p JOIN reservations r ON r.code=p.code SET p.new_booking=FALSE;

  INSERT INTO reservations(code,guest_name,guest_phone,guest_email,table_id,customer_id,reservation_date,reservation_time,
    ends_at,guest_count,note,consent_accepted,status,created_at,deposit_amount,deposit_status)
  SELECT p.code,u.full_name,u.phone,u.email,t.id,IF(p.with_food OR p.booking_status='CONFIRMED',u.id,NULL),
    DATE(slot_start),TIME(slot_start),slot_end,p.guests,
    CASE WHEN p.with_food THEN 'Chuẩn bị món khi nhà hàng đã xác nhận lịch.' ELSE 'Liên hệ qua điện thoại khi cần đổi lịch.' END,
    TRUE,p.booking_status,local_now - INTERVAL 1 MINUTE,
    IF(p.with_food,GREATEST(300000,CEIL(d.price*5*0.70)),0),
    IF(p.with_food,'PENDING_STAFF_CONFIRMATION','NOT_REQUIRED')
  FROM t3l_seed_plan p JOIN app_users u ON u.username=p.user_key AND u.role='BUYER'
  JOIN restaurant_tables t ON t.code=p.table_code AND t.active=TRUE AND t.capacity>=p.guests
  LEFT JOIN dishes d ON d.name='Chạo tôm mía' AND d.available=TRUE AND d.published=TRUE
  WHERE p.new_booking AND (NOT p.with_food OR d.id IS NOT NULL)
    AND NOT EXISTS (
      SELECT 1 FROM reservations current_booking WHERE current_booking.table_id=t.id
        AND current_booking.status IN ('PENDING_CONFIRMATION','CONFIRMED','CHECKED_IN','SEATED')
        AND TIMESTAMP(current_booking.reservation_date,current_booking.reservation_time)<slot_end
        AND COALESCE(current_booking.ends_at,TIMESTAMP(current_booking.reservation_date,current_booking.reservation_time)+INTERVAL duration MINUTE)>slot_start
    );

  INSERT INTO dining_orders(reservation_id,status,opened_by,opened_at)
  SELECT r.id,'DRAFT',u.username,r.created_at FROM t3l_seed_plan p JOIN reservations r ON r.code=p.code
  JOIN app_users u ON u.id=r.customer_id
  WHERE p.new_booking AND p.with_food AND r.status='PENDING_CONFIRMATION'
    AND NOT EXISTS (SELECT 1 FROM dining_orders o WHERE o.reservation_id=r.id);
  INSERT INTO dining_order_items(order_id,dish_id,dish_name,unit_price,quantity,note,preordered)
  SELECT o.id,d.id,d.name,d.price,5,'Không thêm hành sống',TRUE
  FROM t3l_seed_plan p JOIN reservations r ON r.code=p.code JOIN dining_orders o ON o.reservation_id=r.id
  JOIN dishes d ON d.name='Chạo tôm mía' AND d.available=TRUE AND d.published=TRUE
  WHERE p.new_booking AND p.with_food AND r.status='PENDING_CONFIRMATION' AND o.status='DRAFT' AND o.opened_at=r.created_at
    AND NOT EXISTS (SELECT 1 FROM dining_order_items item WHERE item.order_id=o.id);

  -- Các mốc được lấy trực tiếp từ ngày của lượt đặt, đơn và giao dịch liên kết.
  CREATE TEMPORARY TABLE t3l_seed_scope (reservation_id BIGINT PRIMARY KEY);
  INSERT INTO t3l_seed_scope
  SELECT r.id FROM reservations r LEFT JOIN t3l_seed_plan plan ON plan.code=r.code
  WHERE plan.code IS NOT NULL OR r.code IN ('T3L-4F37A1B680E9C522','T3L-937DEA2F618B5E44',
    'T3L-56C0FF1A2E73BB19','T3L-76AB20FC795D2741','T3L-9AF02ED64163CD76','T3L-D8E2C31F597E6047',
    'T3L-0D27C5E9B63A184F','T3L-25BA63EC91D847F2','T3L-E7D94105DB2CF638','T3L-8C4F74D6B9472A10')
    OR r.code IN (SELECT CONCAT('T3L-',LEFT(UPPER(SHA2(CONCAT('t3l-paid-order:',offsets.n),256)),16))
                   FROM (SELECT 2 n UNION ALL SELECT 3 UNION ALL SELECT 5 UNION ALL SELECT 7 UNION ALL SELECT 9
                     UNION ALL SELECT 13 UNION ALL SELECT 21 UNION ALL SELECT 27 UNION ALL SELECT 35 UNION ALL SELECT 60
                     UNION ALL SELECT 90 UNION ALL SELECT 180 UNION ALL SELECT 310) offsets);
  CREATE TEMPORARY TABLE t3l_seed_events (
    reservation_id BIGINT NOT NULL, actor VARCHAR(80), description VARCHAR(250), happened DATETIME(6)
  ) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
  INSERT INTO t3l_seed_events
  SELECT r.id,'Hệ thống','Tiếp nhận yêu cầu đặt bàn.',r.created_at
  FROM reservations r JOIN t3l_seed_scope selected ON selected.reservation_id=r.id;
  INSERT INTO t3l_seed_events
  SELECT r.id,'nguyen.hoang.an','Đã gọi khách và xác nhận lịch đặt.',r.created_at + INTERVAL 15 SECOND
  FROM reservations r JOIN t3l_seed_plan p ON p.code=r.code WHERE p.booking_status='CONFIRMED';
  INSERT INTO t3l_seed_events
  SELECT r.id,o.opened_by,'Đã gọi khách và xác nhận lịch đặt.',r.created_at + INTERVAL 1 MINUTE
  FROM t3l_seed_scope selected JOIN reservations r ON r.id=selected.reservation_id
  JOIN dining_orders o ON o.reservation_id=r.id
  WHERE r.status='COMPLETED' AND r.created_at + INTERVAL 1 MINUTE < r.seated_at
    AND NOT EXISTS (SELECT 1 FROM reservation_events old WHERE old.reservation_id=r.id AND old.description LIKE '%xác nhận%');
  INSERT INTO t3l_seed_events
  SELECT r.id,o.opened_by,'Đã nhận bàn và bắt đầu phục vụ.',r.seated_at
  FROM t3l_seed_scope selected JOIN reservations r ON r.id=selected.reservation_id JOIN dining_orders o ON o.reservation_id=r.id
  WHERE r.seated_at IS NOT NULL;
  INSERT INTO t3l_seed_events
  SELECT r.id,p.received_by,'Đã thanh toán hóa đơn.',p.paid_at FROM t3l_seed_scope selected
  JOIN reservations r ON r.id=selected.reservation_id JOIN dining_orders o ON o.reservation_id=r.id
  JOIN dining_payments p ON p.order_id=o.id;

  INSERT INTO reservation_events(reservation_id,actor,description,created_at)
  SELECT e.reservation_id,e.actor,e.description,e.happened FROM t3l_seed_events e
  WHERE NOT EXISTS (SELECT 1 FROM reservation_events old WHERE old.reservation_id=e.reservation_id AND old.description=e.description);

  INSERT INTO reservation_events(reservation_id,actor,description,created_at)
  SELECT r.id,'Hệ thống','Đã lưu món đặt trước vào lượt đặt bàn.',o.opened_at
  FROM t3l_seed_plan p JOIN reservations r ON r.code=p.code JOIN dining_orders o ON o.reservation_id=r.id
  WHERE p.new_booking AND p.with_food AND EXISTS (SELECT 1 FROM dining_order_items item WHERE item.order_id=o.id AND item.preordered);

  INSERT INTO employee_activity(actor,actor_id,actor_role,operation,target_type,target_id,target_label,details,occurred_at)
  SELECT 'admin',administrator.id,'ADMIN','Tạo bàn','TABLE',t.id,t.code,
    CONCAT('Thêm bàn vào danh mục nhà hàng. Sức chứa ', t.capacity, ' khách.'),
    COALESCE((SELECT MIN(r.created_at) FROM reservations r WHERE r.table_id=t.id),local_now) - INTERVAL 1 SECOND
  FROM restaurant_tables t LEFT JOIN app_users administrator ON administrator.username='admin' AND administrator.role='ADMIN'
  WHERE REGEXP_LIKE(t.code,'^(Bàn 0(0[5-9]|1[0-9]|2[0-4])|VIP-0[1-4])$')
    AND NOT EXISTS (SELECT 1 FROM employee_activity old WHERE old.target_type='TABLE' AND old.target_id=t.id AND old.operation='Tạo bàn');

  INSERT INTO employee_activity(actor,actor_id,actor_role,operation,target_type,target_id,target_label,details,occurred_at)
  SELECT u.username,u.id,u.role,
    CASE e.description WHEN 'Đã gọi khách và xác nhận lịch đặt.' THEN 'Xác nhận đặt bàn'
      WHEN 'Đã nhận bàn và bắt đầu phục vụ.' THEN 'Nhận khách'
      ELSE 'Xác nhận thanh toán hóa đơn' END,
    IF(e.description='Đã thanh toán hóa đơn.','DINING_ORDER','RESERVATION'),
    IF(e.description='Đã thanh toán hóa đơn.',o.id,r.id),
    IF(e.description='Đã thanh toán hóa đơn.',CONCAT('T3L-HD-',o.id),r.code),e.description,e.happened
  FROM t3l_seed_events e JOIN app_users u ON u.username=e.actor
  JOIN reservations r ON r.id=e.reservation_id LEFT JOIN dining_orders o ON o.reservation_id=r.id
  WHERE u.role IN ('STAFF','ADMIN') AND NOT EXISTS (SELECT 1 FROM employee_activity old WHERE old.actor=u.username AND old.details=e.description
    AND old.target_label=IF(e.description='Đã thanh toán hóa đơn.',CONCAT('T3L-HD-',o.id),r.code));

  INSERT INTO employee_activity(actor,actor_id,actor_role,operation,target_type,target_id,target_label,details,occurred_at)
  SELECT u.username,u.id,u.role,m.operation,'SHIFT',sh.id,CONCAT('Ca #',sh.id),
    IF(m.closed,CONCAT('Đã đối soát ca. Tiền mặt kiểm đếm ',sh.counted_cash,' đ.'), 'Đã mở ca để tiếp nhận khách.'),
    IF(m.closed,sh.closed_at,sh.opened_at)
  FROM staff_shifts sh JOIN app_users u ON u.id=sh.employee_id
  CROSS JOIN (SELECT 'Mở ca làm việc' operation,FALSE closed UNION ALL SELECT 'Kết ca',TRUE) m
  WHERE EXISTS (SELECT 1 FROM t3l_seed_scope selected JOIN reservations r ON r.id=selected.reservation_id WHERE r.service_shift_id=sh.id)
    AND (NOT m.closed OR sh.closed_at IS NOT NULL)
    AND NOT EXISTS (SELECT 1 FROM employee_activity old WHERE old.target_type='SHIFT' AND old.target_id=sh.id AND old.operation=m.operation);

  DROP TEMPORARY TABLE t3l_seed_accounts, t3l_seed_plan, t3l_seed_scope, t3l_seed_events;
  COMMIT;
END //
DELIMITER ;

CALL t3l_seed_workflows();
DROP PROCEDURE t3l_seed_workflows;
