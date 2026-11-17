-- Additive, repeatable migration. Existing booking IDs, tables and statuses are preserved.
USE t3l_restaurant;

DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_reservation_timeslots$$
CREATE PROCEDURE migrate_reservation_timeslots()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'reservations' AND column_name = 'ends_at'
  ) THEN
    ALTER TABLE reservations ADD COLUMN ends_at DATETIME NULL AFTER reservation_time;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'reservations' AND column_name = 'preferred_area'
  ) THEN
    ALTER TABLE reservations ADD COLUMN preferred_area VARCHAR(40) NULL AFTER guest_count;
  END IF;
  -- Legacy bookings receive the agreed two-hour duration, including overnight intervals.
  UPDATE reservations
  SET ends_at = DATE_ADD(TIMESTAMP(reservation_date, reservation_time), INTERVAL 120 MINUTE)
  WHERE ends_at IS NULL;
END$$
DELIMITER ;

CALL migrate_reservation_timeslots();
DROP PROCEDURE migrate_reservation_timeslots;
