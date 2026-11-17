-- Nullable profile columns preserve all existing accounts. Safe to run repeatedly on MySQL 8.
SET NAMES utf8mb4;
SET @schema_name = DATABASE();
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'app_users' AND column_name = 'full_name'), 'SELECT 1', 'ALTER TABLE app_users ADD COLUMN full_name VARCHAR(120) NULL');
PREPARE registration_stmt FROM @ddl;
EXECUTE registration_stmt;
DEALLOCATE PREPARE registration_stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'app_users' AND column_name = 'email'), 'SELECT 1', 'ALTER TABLE app_users ADD COLUMN email VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL');
PREPARE registration_stmt FROM @ddl;
EXECUTE registration_stmt;
DEALLOCATE PREPARE registration_stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'app_users' AND column_name = 'phone'), 'SELECT 1', 'ALTER TABLE app_users ADD COLUMN phone VARCHAR(30) NULL');
PREPARE registration_stmt FROM @ddl;
EXECUTE registration_stmt;
DEALLOCATE PREPARE registration_stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'app_users' AND index_name = 'uk_app_users_email'), 'SELECT 1', 'ALTER TABLE app_users ADD CONSTRAINT uk_app_users_email UNIQUE (email)');
PREPARE registration_stmt FROM @ddl;
EXECUTE registration_stmt;
DEALLOCATE PREPARE registration_stmt;
