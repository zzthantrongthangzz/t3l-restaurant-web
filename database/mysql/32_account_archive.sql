-- Lưu trữ tài khoản theo mã hiện tại để giữ liên kết với lịch sử.
DROP PROCEDURE IF EXISTS t3l_migrate_account_archive;
DELIMITER //
CREATE PROCEDURE t3l_migrate_account_archive()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
      AND table_name = 'app_users' AND column_name = 'archived_at') THEN
    ALTER TABLE app_users ADD COLUMN archived_at DATETIME NULL;
  END IF;
END //
DELIMITER ;
CALL t3l_migrate_account_archive();
DROP PROCEDURE t3l_migrate_account_archive;
