-- Chạy một lần để chuyển database cũ có chi nhánh/khu sang mô hình một nhà hàng.
-- Các bàn trùng mã được gộp theo mã; reservation hiện có được trỏ về bàn giữ lại.
USE t3l_restaurant;

CREATE TEMPORARY TABLE restaurant_table_map AS
SELECT code, MIN(id) AS canonical_id
FROM restaurant_tables
GROUP BY code;

UPDATE reservations r
JOIN restaurant_tables old_table ON old_table.id = r.table_id
JOIN restaurant_table_map mapped ON mapped.code = old_table.code
SET r.table_id = mapped.canonical_id;

DELETE old_table
FROM restaurant_tables old_table
JOIN restaurant_table_map mapped ON mapped.code = old_table.code
WHERE old_table.id <> mapped.canonical_id;

DROP TEMPORARY TABLE restaurant_table_map;

DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_single_restaurant$$
DROP PROCEDURE IF EXISTS migrate_dishes$$
CREATE PROCEDURE migrate_single_restaurant()
BEGIN
  DECLARE fk_name VARCHAR(64);
  DECLARE index_name VARCHAR(64);

  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 't3l_restaurant' AND table_name = 'reservations' AND column_name = 'branch_id'
  ) THEN
    SELECT CONSTRAINT_NAME INTO fk_name
    FROM information_schema.key_column_usage
    WHERE table_schema = 't3l_restaurant' AND table_name = 'reservations'
      AND column_name = 'branch_id' AND referenced_table_name = 'branches'
    LIMIT 1;
    IF fk_name IS NOT NULL THEN
      SET @ddl = CONCAT('ALTER TABLE reservations DROP FOREIGN KEY `', fk_name, '`');
      PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;
    END IF;
    IF EXISTS (
      SELECT 1 FROM information_schema.statistics
      WHERE table_schema = 't3l_restaurant' AND table_name = 'reservations' AND index_name = 'idx_reservations_schedule'
    ) THEN
      ALTER TABLE reservations DROP INDEX idx_reservations_schedule;
    END IF;
    ALTER TABLE reservations DROP COLUMN branch_id;
  END IF;

  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 't3l_restaurant' AND table_name = 'restaurant_tables' AND column_name = 'branch_id'
  ) THEN
    SELECT CONSTRAINT_NAME INTO fk_name
    FROM information_schema.key_column_usage
    WHERE table_schema = 't3l_restaurant' AND table_name = 'restaurant_tables'
      AND column_name = 'branch_id' AND referenced_table_name = 'branches'
    LIMIT 1;
    IF fk_name IS NOT NULL THEN
      SET @ddl = CONCAT('ALTER TABLE restaurant_tables DROP FOREIGN KEY `', fk_name, '`');
      PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;
    END IF;
    SELECT INDEX_NAME INTO index_name
    FROM information_schema.statistics
    WHERE table_schema = 't3l_restaurant' AND table_name = 'restaurant_tables'
      AND column_name = 'branch_id' AND non_unique = 0
    LIMIT 1;
    IF index_name IS NOT NULL THEN
      SET @ddl = CONCAT('ALTER TABLE restaurant_tables DROP INDEX `', index_name, '`');
      PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;
    END IF;
    ALTER TABLE restaurant_tables DROP COLUMN branch_id;
  END IF;

  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 't3l_restaurant' AND table_name = 'restaurant_tables' AND column_name = 'zone_name'
  ) THEN
    ALTER TABLE restaurant_tables DROP COLUMN zone_name;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = 't3l_restaurant' AND table_name = 'restaurant_tables'
      AND index_name = 'uk_restaurant_tables_code'
  ) THEN
    ALTER TABLE restaurant_tables ADD UNIQUE KEY uk_restaurant_tables_code (code);
  END IF;

  DROP TABLE IF EXISTS branches;
END$$

CREATE PROCEDURE migrate_dishes()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 't3l_restaurant' AND table_name = 'dishes' AND column_name = 'image_public_id'
  ) THEN
    ALTER TABLE dishes ADD COLUMN image_public_id VARCHAR(255) NULL AFTER image_url;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = 't3l_restaurant' AND table_name = 'dishes' AND index_name = 'uk_dishes_name'
  ) THEN
    ALTER TABLE dishes ADD UNIQUE KEY uk_dishes_name (name);
  END IF;
END$$
DELIMITER ;

CALL migrate_single_restaurant();
CALL migrate_dishes();
DROP PROCEDURE migrate_single_restaurant;
DROP PROCEDURE migrate_dishes;

SET @reservation_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = 't3l_restaurant' AND table_name = 'reservations'
    AND index_name = 'idx_reservations_schedule'
);
SET @ddl = IF(
  @reservation_index_exists = 0,
  'ALTER TABLE reservations ADD INDEX idx_reservations_schedule (reservation_date, status)',
  'SELECT 1'
);
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

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
