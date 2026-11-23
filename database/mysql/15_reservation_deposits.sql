-- Thêm trạng thái và mã giao dịch cọc cho đặt bàn khách hàng.
USE t3l_restaurant;
DROP PROCEDURE IF EXISTS t3l_add_reservation_deposit_columns;
DELIMITER //
CREATE PROCEDURE t3l_add_reservation_deposit_columns()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='reservations' AND column_name='deposit_amount') THEN
    ALTER TABLE reservations ADD COLUMN deposit_amount DECIMAL(16,0) NOT NULL DEFAULT 0;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='reservations' AND column_name='deposit_status') THEN
    ALTER TABLE reservations ADD COLUMN deposit_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='reservations' AND column_name='deposit_reference') THEN
    ALTER TABLE reservations ADD COLUMN deposit_reference VARCHAR(80);
  END IF;
END //
DELIMITER ;
CALL t3l_add_reservation_deposit_columns();
DROP PROCEDURE t3l_add_reservation_deposit_columns;
