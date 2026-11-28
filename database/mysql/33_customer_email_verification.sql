-- Email cũ giữ nguyên và chỉ được đánh dấu sau khi khách xác minh.
DROP PROCEDURE IF EXISTS t3l_migrate_customer_email_verification;
DELIMITER //
CREATE PROCEDURE t3l_migrate_customer_email_verification()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
      AND table_name = 'app_users' AND column_name = 'email_verified_at') THEN
    ALTER TABLE app_users ADD COLUMN email_verified_at DATETIME(6) NULL;
  END IF;
END //
DELIMITER ;
CALL t3l_migrate_customer_email_verification();
DROP PROCEDURE t3l_migrate_customer_email_verification;
