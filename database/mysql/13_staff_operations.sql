-- MySQL 8. Add staff operations without rewriting existing booking and sales data.
USE t3l_restaurant;

DROP PROCEDURE IF EXISTS t3l_add_staff_reservation_columns;
DELIMITER //
CREATE PROCEDURE t3l_add_staff_reservation_columns()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
      AND table_name = 'reservations' AND column_name = 'checked_in_at') THEN
    ALTER TABLE reservations ADD COLUMN checked_in_at DATETIME(6);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
      AND table_name = 'reservations' AND column_name = 'seated_at') THEN
    ALTER TABLE reservations ADD COLUMN seated_at DATETIME(6);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
      AND table_name = 'reservations' AND column_name = 'completed_at') THEN
    ALTER TABLE reservations ADD COLUMN completed_at DATETIME(6);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
      AND table_name = 'reservations' AND column_name = 'service_shift_id') THEN
    ALTER TABLE reservations ADD COLUMN service_shift_id BIGINT;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.key_column_usage WHERE table_schema = DATABASE()
      AND table_name = 'reservations' AND column_name = 'service_shift_id' AND referenced_table_name = 'staff_shifts') THEN
    ALTER TABLE reservations ADD CONSTRAINT fk_reservations_service_shift FOREIGN KEY (service_shift_id) REFERENCES staff_shifts(id);
  END IF;
END //
DELIMITER ;
CALL t3l_add_staff_reservation_columns();
DROP PROCEDURE t3l_add_staff_reservation_columns;

ALTER TABLE staff_shifts
  MODIFY cash_sales DECIMAL(18,0), MODIFY other_sales DECIMAL(18,0),
  MODIFY counted_cash DECIMAL(18,0), MODIFY cash_difference DECIMAL(18,0);

CREATE TABLE IF NOT EXISTS reservation_events (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  reservation_id BIGINT NOT NULL,
  actor VARCHAR(80) NOT NULL,
  description VARCHAR(500) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  INDEX ix_reservation_events_history (reservation_id,id),
  CONSTRAINT fk_reservation_event_booking FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS dining_orders (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  reservation_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  opened_by VARCHAR(80) NOT NULL,
  opened_at DATETIME(6) NOT NULL,
  finalized_at DATETIME(6),
  total_amount DECIMAL(16,0),
  UNIQUE KEY uk_dining_order_reservation (reservation_id),
  CONSTRAINT fk_dining_order_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS dining_order_items (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  dish_id BIGINT NOT NULL,
  dish_name VARCHAR(140) NOT NULL,
  unit_price DECIMAL(12,0) NOT NULL,
  quantity INT NOT NULL,
  cancelled BOOLEAN NOT NULL DEFAULT FALSE,
  INDEX ix_dining_items_order (order_id,id),
  CONSTRAINT fk_dining_item_order FOREIGN KEY (order_id) REFERENCES dining_orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS dining_payments (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  shift_id BIGINT NOT NULL,
  amount DECIMAL(16,0) NOT NULL,
  payment_method VARCHAR(20) NOT NULL,
  transfer_reference VARCHAR(80) COLLATE utf8mb4_0900_as_ci,
  received_by VARCHAR(80) NOT NULL,
  paid_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_dining_payment_order (order_id),
  UNIQUE KEY uk_dining_payment_transfer (transfer_reference),
  INDEX ix_dining_payment_shift (shift_id,paid_at),
  CONSTRAINT fk_dining_payment_order FOREIGN KEY (order_id) REFERENCES dining_orders(id),
  CONSTRAINT fk_dining_payment_shift FOREIGN KEY (shift_id) REFERENCES staff_shifts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
