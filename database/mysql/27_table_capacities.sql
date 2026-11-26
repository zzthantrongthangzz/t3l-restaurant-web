-- Áp dụng sau 16_table_kinds.sql. Giữ nguyên mã bàn và các lượt đặt.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

DROP PROCEDURE IF EXISTS t3l_standardize_table_capacities;
DELIMITER //
CREATE PROCEDURE t3l_standardize_table_capacities()
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    RESIGNAL;
  END;

  START TRANSACTION;

  UPDATE restaurant_tables
  SET table_kind = CASE
    WHEN UPPER(table_kind) IN ('SMALL', 'MEDIUM', 'LARGE', 'VIP') THEN UPPER(table_kind)
    WHEN table_kind = 'Phòng VIP' THEN 'VIP'
    WHEN table_kind = 'Bàn lớn' THEN 'LARGE'
    WHEN table_kind = 'Bàn vừa' THEN 'MEDIUM'
    WHEN table_kind = 'Bàn nhỏ' THEN 'SMALL'
    WHEN LOWER(table_type) LIKE '%vip%' THEN 'VIP'
    WHEN LOWER(table_type) LIKE '%lớn%' THEN 'LARGE'
    WHEN LOWER(table_type) LIKE '%vừa%' THEN 'MEDIUM'
    WHEN capacity > 8 THEN 'LARGE'
    WHEN capacity > 4 THEN 'MEDIUM'
    ELSE 'SMALL'
  END;

  IF EXISTS (
    SELECT 1 FROM reservations r JOIN restaurant_tables t ON t.id = r.table_id
    WHERE r.status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'CHECKED_IN', 'SEATED')
      AND (r.reservation_date >= CURRENT_DATE OR r.status IN ('CHECKED_IN', 'SEATED'))
      AND r.guest_count > CASE t.table_kind WHEN 'SMALL' THEN 4 WHEN 'MEDIUM' THEN 8 WHEN 'LARGE' THEN 16 ELSE 30 END
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Co luot dat dang mo vuot suc chua moi. Hay xu ly luot dat truoc khi cap nhat.';
  END IF;

  UPDATE restaurant_tables
  SET capacity = CASE table_kind WHEN 'SMALL' THEN 4 WHEN 'MEDIUM' THEN 8 WHEN 'LARGE' THEN 16 ELSE 30 END;

  COMMIT;
END//
DELIMITER ;

CALL t3l_standardize_table_capacities();
DROP PROCEDURE t3l_standardize_table_capacities;
