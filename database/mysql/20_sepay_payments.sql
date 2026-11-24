-- SePay polling intents and DEMO accounting markers.
USE t3l_restaurant;

CREATE TABLE IF NOT EXISTS payment_intents (
  id BIGINT NOT NULL AUTO_INCREMENT,
  payment_code VARCHAR(12) NOT NULL,
  payment_type VARCHAR(16) NOT NULL,
  reservation_id BIGINT NULL,
  order_id BIGINT NULL,
  amount DECIMAL(16,0) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  is_demo BOOLEAN NOT NULL DEFAULT FALSE,
  provider_transaction_id VARCHAR(80) NULL,
  provider_reference VARCHAR(100) NULL,
  received_account VARCHAR(80) NULL,
  transaction_content VARCHAR(255) NULL,
  transaction_at DATETIME NULL,
  review_reason VARCHAR(120) NULL,
  created_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  received_at DATETIME NULL,
  applied_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_intent_code (payment_code),
  UNIQUE KEY uk_payment_intent_provider_tx (provider_transaction_id),
  KEY ix_payment_intent_reservation (reservation_id,payment_type,id),
  KEY ix_payment_intent_order (order_id,payment_type,id),
  KEY ix_payment_intent_poll (status,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP PROCEDURE IF EXISTS t3l_migrate_sepay_payments;
DELIMITER //
CREATE PROCEDURE t3l_migrate_sepay_payments()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='reservations' AND column_name='deposit_demo') THEN
    ALTER TABLE reservations ADD COLUMN deposit_demo BOOLEAN NOT NULL DEFAULT FALSE;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='dining_payments' AND column_name='is_demo') THEN
    ALTER TABLE dining_payments ADD COLUMN is_demo BOOLEAN NOT NULL DEFAULT FALSE;
  END IF;
END //
DELIMITER ;
CALL t3l_migrate_sepay_payments();
DROP PROCEDURE t3l_migrate_sepay_payments;
