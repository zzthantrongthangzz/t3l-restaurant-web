-- Bổ sung bàn lớn và bàn nhỏ theo danh mục mặc định.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

DROP PROCEDURE IF EXISTS t3l_add_large_tables;
DELIMITER //
CREATE PROCEDURE t3l_add_large_tables()
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    RESIGNAL;
  END;

  START TRANSACTION;

  UPDATE restaurant_tables SET capacity = 15 WHERE table_kind = 'LARGE' AND capacity <> 15;

  IF EXISTS (
    SELECT 1 FROM reservations r JOIN restaurant_tables t ON t.id = r.table_id
    WHERE t.table_kind = 'LARGE' AND r.guest_count > t.capacity
      AND r.status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'CHECKED_IN', 'SEATED')
      AND (r.reservation_date >= CURRENT_DATE OR r.status IN ('CHECKED_IN', 'SEATED'))
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Co luot dat ban lon qua 15 khach. Hay xu ly luot dat truoc khi cap nhat.';
  END IF;

  INSERT INTO restaurant_tables (code, table_type, table_kind, capacity, active) VALUES
    ('Bàn 019', 'Bàn lớn', 'LARGE', 15, TRUE),
    ('Bàn 020', 'Bàn lớn', 'LARGE', 15, TRUE),
    ('Bàn 021', 'Bàn lớn', 'LARGE', 15, TRUE),
    ('Bàn 022', 'Bàn lớn', 'LARGE', 15, TRUE),
    ('Bàn 023', 'Bàn lớn', 'LARGE', 15, TRUE),
    ('Bàn 024', 'Bàn nhỏ', 'SMALL', 4, TRUE)
  ON DUPLICATE KEY UPDATE code = restaurant_tables.code;

  IF EXISTS (
    SELECT 1 FROM restaurant_tables
    WHERE (code IN ('Bàn 019', 'Bàn 020', 'Bàn 021', 'Bàn 022', 'Bàn 023')
        AND COALESCE(table_kind, '') <> 'LARGE')
      OR (code = 'Bàn 024' AND COALESCE(table_kind, '') <> 'SMALL')
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Ma ban moi dang duoc su dung cho loai ban khac.';
  END IF;

  COMMIT;
END//
DELIMITER ;

CALL t3l_add_large_tables();
DROP PROCEDURE t3l_add_large_tables;
