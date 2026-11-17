CREATE TABLE IF NOT EXISTS contact_messages (
  id BIGINT NOT NULL AUTO_INCREMENT,
  version BIGINT NOT NULL DEFAULT 0,
  name VARCHAR(120) NOT NULL,
  email VARCHAR(254) NOT NULL,
  phone VARCHAR(30) NOT NULL,
  subject VARCHAR(150) NOT NULL,
  message VARCHAR(4000) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'NEW',
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  handled_by VARCHAR(100),
  admin_note VARCHAR(1000),
  PRIMARY KEY (id),
  INDEX idx_contact_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
