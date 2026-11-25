CREATE TABLE IF NOT EXISTS vouchers (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(32) NOT NULL,
  title VARCHAR(120) NOT NULL,
  discount_type VARCHAR(10) NOT NULL,
  discount_value DECIMAL(18,2) NOT NULL,
  minimum_total DECIMAL(16,0) NOT NULL,
  maximum_discount DECIMAL(16,0) NULL,
  starts_at DATETIME(6) NOT NULL,
  ends_at DATETIME(6) NOT NULL,
  total_limit INT NULL,
  customer_limit INT NULL,
  public_offer BOOLEAN NOT NULL,
  enabled BOOLEAN NOT NULL,
  CONSTRAINT uk_voucher_code UNIQUE (code),
  INDEX ix_voucher_public (public_offer, enabled, ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS voucher_usages (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  voucher_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  active_order_id BIGINT NULL,
  customer_key VARCHAR(80) NULL,
  code VARCHAR(32) NOT NULL,
  discount_type VARCHAR(10) NOT NULL,
  discount_value DECIMAL(18,2) NOT NULL,
  minimum_total DECIMAL(16,0) NOT NULL,
  maximum_discount DECIMAL(16,0) NULL,
  discount_amount DECIMAL(16,0) NOT NULL,
  status VARCHAR(10) NOT NULL,
  held_at DATETIME(6) NOT NULL,
  closed_at DATETIME(6) NULL,
  demo BOOLEAN NOT NULL,
  CONSTRAINT fk_voucher_usage_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers(id),
  CONSTRAINT fk_voucher_usage_order FOREIGN KEY (order_id) REFERENCES dining_orders(id),
  CONSTRAINT uk_voucher_usage_active_order UNIQUE (active_order_id),
  INDEX ix_voucher_usage_limits (voucher_id, status, demo, customer_key),
  INDEX ix_voucher_usage_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP PROCEDURE IF EXISTS t3l_add_voucher_snapshot;
DELIMITER //
CREATE PROCEDURE t3l_add_voucher_snapshot()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='dining_orders' AND column_name='voucher_code') THEN
    ALTER TABLE dining_orders ADD COLUMN voucher_code VARCHAR(32) NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='dining_orders' AND column_name='voucher_discount') THEN
    ALTER TABLE dining_orders ADD COLUMN voucher_discount DECIMAL(16,0) NULL;
  END IF;
END //
DELIMITER ;
CALL t3l_add_voucher_snapshot();
DROP PROCEDURE t3l_add_voucher_snapshot;
