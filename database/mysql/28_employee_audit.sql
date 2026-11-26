-- Run as the migration owner. The application runtime user needs SELECT and INSERT only on the audit table.
CREATE TABLE IF NOT EXISTS employee_activity (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  actor VARCHAR(80) NOT NULL,
  actor_id BIGINT NULL,
  actor_role VARCHAR(16) NOT NULL,
  operation VARCHAR(80) NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_id BIGINT NULL,
  target_label VARCHAR(250) NOT NULL,
  details VARCHAR(500),
  occurred_at DATETIME(6) NOT NULL,
  INDEX ix_employee_activity_time (occurred_at,id),
  INDEX ix_employee_activity_actor_time (actor,occurred_at,id),
  INDEX ix_employee_activity_target (target_type,target_id,occurred_at)
);

SET @audit_column = IF(EXISTS(
  SELECT 1 FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employee_activity' AND COLUMN_NAME = 'actor_id'
), 'DO 0', 'ALTER TABLE employee_activity ADD COLUMN actor_id BIGINT NULL');
PREPARE add_audit_actor FROM @audit_column;
EXECUTE add_audit_actor;
DEALLOCATE PREPARE add_audit_actor;

CREATE TRIGGER IF NOT EXISTS employee_activity_no_update BEFORE UPDATE ON employee_activity
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Audit history is append-only';
CREATE TRIGGER IF NOT EXISTS employee_activity_no_delete BEFORE DELETE ON employee_activity
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Audit history is append-only';
CREATE TRIGGER IF NOT EXISTS admin_activity_no_update BEFORE UPDATE ON admin_activity
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy audit history is read-only';
CREATE TRIGGER IF NOT EXISTS admin_activity_no_delete BEFORE DELETE ON admin_activity
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy audit history is read-only';
