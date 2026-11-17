-- Additive, repeatable migration. No existing reservation is changed or removed.
USE t3l_restaurant;
CREATE TABLE IF NOT EXISTS reservation_change_requests (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  reservation_id BIGINT NOT NULL,
  request_type VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  original_date DATE NOT NULL,
  original_time TIME NOT NULL,
  original_guests INT NOT NULL,
  original_area VARCHAR(40),
  requested_date DATE,
  requested_time TIME,
  requested_guests INT,
  requested_area VARCHAR(40),
  reason VARCHAR(500),
  created_at DATETIME NOT NULL,
  reviewed_at DATETIME,
  reviewed_by VARCHAR(80),
  review_note VARCHAR(500),
  pending_reservation_id BIGINT GENERATED ALWAYS AS
    (CASE WHEN status = 'PENDING' THEN reservation_id ELSE NULL END) STORED,
  CONSTRAINT fk_change_request_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id),
  UNIQUE KEY uk_change_request_pending (pending_reservation_id),
  INDEX idx_change_request_history (reservation_id, created_at),
  INDEX idx_change_request_queue (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
