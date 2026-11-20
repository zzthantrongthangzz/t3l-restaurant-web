-- Additive ADMIN workspace schema. Existing roles, bookings, prices and payments stay intact.
USE t3l_restaurant;
DROP PROCEDURE IF EXISTS t3l_add_admin_workspace_columns;
DELIMITER //
CREATE PROCEDURE t3l_add_admin_workspace_columns()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='app_users' AND column_name='auth_version') THEN
    ALTER TABLE app_users ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='dishes' AND column_name='published') THEN
    ALTER TABLE dishes ADD COLUMN published BOOLEAN NOT NULL DEFAULT TRUE;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='restaurant_tables' AND column_name='table_kind') THEN
    ALTER TABLE restaurant_tables ADD COLUMN table_kind VARCHAR(40);
  END IF;
END //
DELIMITER ;
CALL t3l_add_admin_workspace_columns();
DROP PROCEDURE t3l_add_admin_workspace_columns;

CREATE TABLE IF NOT EXISTS restaurant_sections (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  kind VARCHAR(8) NOT NULL,
  name VARCHAR(80) NOT NULL,
  active BOOLEAN NOT NULL,
  UNIQUE KEY uk_restaurant_section_kind_name (kind,name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO restaurant_sections(kind,name,active)
SELECT 'AREA', names.name, TRUE FROM (SELECT DISTINCT table_type AS name FROM restaurant_tables) names
WHERE NOT EXISTS (SELECT 1 FROM restaurant_sections s WHERE s.kind='AREA' AND s.name=names.name COLLATE utf8mb4_0900_ai_ci);
INSERT INTO restaurant_sections(kind,name,active)
SELECT 'MENU', names.name, TRUE FROM (SELECT DISTINCT category_name AS name FROM dishes) names
WHERE NOT EXISTS (SELECT 1 FROM restaurant_sections s WHERE s.kind='MENU' AND s.name=names.name COLLATE utf8mb4_0900_ai_ci);

CREATE TABLE IF NOT EXISTS restaurant_settings (
  id INT NOT NULL PRIMARY KEY,
  address VARCHAR(300) NOT NULL, phone VARCHAR(30) NOT NULL, email VARCHAR(254) NOT NULL,
  about_text VARCHAR(2000) NOT NULL, about_image_url VARCHAR(500) NOT NULL,
  opening_time TIME NOT NULL, closing_time TIME NOT NULL,
  duration_minutes INT NOT NULL, slot_minutes INT NOT NULL, change_notice_minutes INT NOT NULL,
  check_in_minutes INT NOT NULL, no_show_minutes INT NOT NULL,
  version BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS admin_activity (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  actor VARCHAR(80) NOT NULL,
  operation VARCHAR(32) NOT NULL,
  target VARCHAR(250) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  INDEX ix_admin_activity_time (occurred_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
