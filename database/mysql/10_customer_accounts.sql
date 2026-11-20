-- Additive migration for customer accounts. Existing reservations remain unassigned.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS customer_profiles (
  user_id BIGINT NOT NULL PRIMARY KEY,
  family_name VARCHAR(120),
  given_name VARCHAR(120),
  dietary_preferences VARCHAR(500),
  reservation_email BOOLEAN NOT NULL DEFAULT FALSE,
  order_email BOOLEAN NOT NULL DEFAULT FALSE,
  google_subject VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin,
  google_name VARCHAR(120),
  google_email VARCHAR(254),
  avatar_url VARCHAR(2048),
  company_name VARCHAR(180),
  company_tax_code VARCHAR(30),
  company_address VARCHAR(300),
  invoice_email VARCHAR(254),
  CONSTRAINT uk_customer_profiles_google_subject UNIQUE (google_subject),
  CONSTRAINT fk_customer_profiles_user FOREIGN KEY (user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS customer_addresses (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  recipient_name VARCHAR(120) NOT NULL,
  phone VARCHAR(30) NOT NULL,
  street_address VARCHAR(300) NOT NULL,
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  INDEX ix_customer_addresses_user (user_id),
  CONSTRAINT fk_customer_addresses_user FOREIGN KEY (user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @schema_name = DATABASE();
-- Align databases where Hibernate already created the profile table.
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'customer_profiles' AND column_name = 'google_subject' AND collation_name <> 'ascii_bin'), 'ALTER TABLE customer_profiles MODIFY COLUMN google_subject VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL', 'SELECT 1');
PREPARE account_stmt FROM @ddl;
EXECUTE account_stmt;
DEALLOCATE PREPARE account_stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'reservations' AND column_name = 'customer_id'), 'SELECT 1', 'ALTER TABLE reservations ADD COLUMN customer_id BIGINT NULL');
PREPARE account_stmt FROM @ddl;
EXECUTE account_stmt;
DEALLOCATE PREPARE account_stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'reservations' AND index_name = 'ix_reservations_customer_schedule'), 'SELECT 1', 'ALTER TABLE reservations ADD INDEX ix_reservations_customer_schedule (customer_id, reservation_date, reservation_time)');
PREPARE account_stmt FROM @ddl;
EXECUTE account_stmt;
DEALLOCATE PREPARE account_stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema = @schema_name AND table_name = 'reservations' AND constraint_name = 'fk_reservations_customer'), 'SELECT 1', 'ALTER TABLE reservations ADD CONSTRAINT fk_reservations_customer FOREIGN KEY (customer_id) REFERENCES app_users(id)');
PREPARE account_stmt FROM @ddl;
EXECUTE account_stmt;
DEALLOCATE PREPARE account_stmt;
