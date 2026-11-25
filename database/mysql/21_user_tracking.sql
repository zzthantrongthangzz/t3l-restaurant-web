CREATE TABLE IF NOT EXISTS tracking_sessions (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  browser_id VARCHAR(36) NOT NULL,
  user_id BIGINT NULL,
  started_at DATETIME(6) NOT NULL,
  last_activity_at DATETIME(6) NOT NULL,
  ended_at DATETIME(6) NULL,
  request_window DATETIME(6) NULL,
  request_count INT NOT NULL,
  INDEX ix_tracking_browser_activity (browser_id, last_activity_at),
  INDEX ix_tracking_session_expiry (last_activity_at),
  INDEX ix_tracking_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tracking_events (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(36) NOT NULL,
  event_key VARCHAR(100) NOT NULL,
  event_type VARCHAR(25) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  actor_kind VARCHAR(8) NOT NULL,
  page_key VARCHAR(30) NOT NULL,
  dish_id BIGINT NULL,
  term VARCHAR(90) NULL,
  quantity INT NULL,
  active_seconds INT NULL,
  flow_key VARCHAR(36) NULL,
  reservation_id BIGINT NULL,
  requires_deposit BOOLEAN NULL,
  CONSTRAINT fk_tracking_event_session FOREIGN KEY (session_id) REFERENCES tracking_sessions(id) ON DELETE CASCADE,
  UNIQUE KEY uk_tracking_event_key (event_key),
  INDEX ix_tracking_time_actor (occurred_at, actor_kind, event_type),
  INDEX ix_tracking_type_time (event_type, occurred_at),
  INDEX ix_tracking_flow (flow_key, event_type),
  INDEX ix_tracking_reservation (reservation_id, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
