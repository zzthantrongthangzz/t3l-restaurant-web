-- Chuyển các lượt từng gắn với sáu bàn thử cũ về danh mục hiện tại.
-- Chỉ chạy trên bản nâng cấp. Sao lưu MySQL trước khi thực hiện.
USE t3l_restaurant;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

DROP PROCEDURE IF EXISTS t3l_consolidate_inventory;
DELIMITER //
CREATE PROCEDURE t3l_consolidate_inventory()
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    DROP TEMPORARY TABLE IF EXISTS t3l_table_mapping;
    RESIGNAL;
  END;

  START TRANSACTION;
  CREATE TEMPORARY TABLE t3l_table_mapping (
    old_id BIGINT PRIMARY KEY, destination BIGINT
  );
  INSERT INTO t3l_table_mapping
  SELECT src.id, dst.id
  FROM restaurant_tables src
  LEFT JOIN restaurant_tables dst ON dst.code=CASE src.code
    WHEN 'DEMO-01' THEN 'Bàn 005' WHEN 'DEMO-02' THEN 'Bàn 006'
    WHEN 'DEMO-03' THEN 'Bàn 007' WHEN 'DEMO-04' THEN 'Bàn 014'
    WHEN 'DEMO-05' THEN 'Bàn 015' WHEN 'DEMO-06' THEN 'Bàn 016' END
    AND dst.table_kind=src.table_kind AND dst.capacity>=src.capacity AND dst.active=TRUE
  WHERE src.code IN ('DEMO-01','DEMO-02','DEMO-03','DEMO-04','DEMO-05','DEMO-06');

  IF EXISTS (
        SELECT 1 FROM t3l_table_mapping m
        LEFT JOIN reservations src ON src.table_id=m.old_id
          AND src.status IN ('PENDING_CONFIRMATION','CONFIRMED','CHECKED_IN','SEATED')
        LEFT JOIN reservations dst ON dst.table_id=m.destination
          AND dst.status IN ('PENDING_CONFIRMATION','CONFIRMED','CHECKED_IN','SEATED')
        WHERE m.destination IS NULL OR (src.id IS NOT NULL AND dst.id IS NOT NULL)
      ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Bàn nhận thiếu, sai loại hoặc đang có lượt mở. Kiểm tra danh mục và lịch trước khi chuyển.';
  END IF;

  UPDATE reservations r JOIN t3l_table_mapping m ON m.old_id=r.table_id SET r.table_id=m.destination;
  DELETE t FROM restaurant_tables t JOIN t3l_table_mapping m ON m.old_id=t.id;

  UPDATE reservation_emails e JOIN reservations r ON r.id=e.reservation_id
  SET e.booking_code=CONCAT('T3L-',LEFT(UPPER(SHA2(r.code,256)),16))
  WHERE r.code LIKE 'T3L-DEMO-%' OR r.code LIKE 'T3L-SAMPLE-%';
  UPDATE reservations SET code=CONCAT('T3L-',LEFT(UPPER(SHA2(code,256)),16))
  WHERE code LIKE 'T3L-DEMO-%' OR code LIKE 'T3L-SAMPLE-%';

  UPDATE reservation_events SET description='Ghi nhận lượt đặt bàn.'
  WHERE description='Dữ liệu đặt bàn demo được tạo.';
  UPDATE payment_reference_claims c JOIN dining_payments p ON p.order_id=c.record_id AND c.purpose='INVOICE'
  SET c.reference_code=CONCAT('FT',LEFT(UPPER(SHA2(p.transfer_reference,256)),12))
  WHERE p.transfer_reference LIKE 'T3L-DEMO-XF-%' AND c.reference_code=p.transfer_reference;
  UPDATE dining_payments SET transfer_reference=CONCAT('FT',LEFT(UPPER(SHA2(transfer_reference,256)),12))
  WHERE transfer_reference LIKE 'T3L-DEMO-XF-%';

  DROP TEMPORARY TABLE t3l_table_mapping;
  COMMIT;
END //
DELIMITER ;
CALL t3l_consolidate_inventory();
DROP PROCEDURE t3l_consolidate_inventory;
