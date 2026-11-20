-- Keep contact phone numbers as entered. Only an OTP verification can set verified_phone.
SET @schema_name = DATABASE();
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='app_users' AND column_name='verified_phone'), 'SELECT 1', 'ALTER TABLE app_users ADD COLUMN verified_phone VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL');
PREPARE sms_stmt FROM @ddl;
EXECUTE sms_stmt;
DEALLOCATE PREPARE sms_stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='app_users' AND index_name='uk_app_users_verified_phone'), 'SELECT 1', 'ALTER TABLE app_users ADD CONSTRAINT uk_app_users_verified_phone UNIQUE (verified_phone)');
PREPARE sms_stmt FROM @ddl;
EXECUTE sms_stmt;
DEALLOCATE PREPARE sms_stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='customer_profiles' AND column_name='booking_notifications'), 'SELECT 1', 'ALTER TABLE customer_profiles ADD COLUMN booking_notifications BOOLEAN NOT NULL DEFAULT FALSE');
PREPARE sms_stmt FROM @ddl;
EXECUTE sms_stmt;
DEALLOCATE PREPARE sms_stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='customer_profiles' AND column_name='marketing_opt_in'), 'SELECT 1', 'ALTER TABLE customer_profiles ADD COLUMN marketing_opt_in BOOLEAN NOT NULL DEFAULT FALSE');
PREPARE sms_stmt FROM @ddl;
EXECUTE sms_stmt;
DEALLOCATE PREPARE sms_stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='reservations' AND column_name='guest_email' AND character_maximum_length < 254), 'ALTER TABLE reservations MODIFY COLUMN guest_email VARCHAR(254) NULL', 'SELECT 1');
PREPARE sms_stmt FROM @ddl;
EXECUTE sms_stmt;
DEALLOCATE PREPARE sms_stmt;

CREATE TABLE IF NOT EXISTS sms_otp_limits (
  rate_key VARCHAR(100) NOT NULL PRIMARY KEY,
  hits INT NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  last_hit_at DATETIME(6) NOT NULL,
  INDEX ix_sms_otp_limits_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
