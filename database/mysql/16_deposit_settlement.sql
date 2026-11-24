-- Add deposit settlement and transfer claims without rewriting existing invoices.
USE t3l_restaurant;

CREATE TABLE IF NOT EXISTS payment_reference_claims (
  reference_code VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci PRIMARY KEY,
  purpose VARCHAR(16) NOT NULL,
  record_id BIGINT NOT NULL
);

DROP PROCEDURE IF EXISTS t3l_migrate_deposit_settlement;
DELIMITER //
CREATE PROCEDURE t3l_migrate_deposit_settlement()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='reservations' AND column_name='deposit_paid') THEN
    ALTER TABLE reservations ADD COLUMN deposit_paid DECIMAL(16,0) NOT NULL DEFAULT 0;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='reservations' AND column_name='deposit_submitted_amount') THEN
    ALTER TABLE reservations ADD COLUMN deposit_submitted_amount DECIMAL(16,0) NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='reservations' AND column_name='deposit_due_at') THEN
    ALTER TABLE reservations ADD COLUMN deposit_due_at DATETIME NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='reservations' AND column_name='deposit_review_due_at') THEN
    ALTER TABLE reservations ADD COLUMN deposit_review_due_at DATETIME NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='reservations' AND column_name='deposit_refund') THEN
    ALTER TABLE reservations ADD COLUMN deposit_refund DECIMAL(16,0) NOT NULL DEFAULT 0;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='reservations' AND column_name='deposit_refunded') THEN
    ALTER TABLE reservations ADD COLUMN deposit_refunded DECIMAL(16,0) NOT NULL DEFAULT 0;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='reservations' AND column_name='deposit_retained') THEN
    ALTER TABLE reservations ADD COLUMN deposit_retained DECIMAL(16,0) NOT NULL DEFAULT 0;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='reservations' AND column_name='deposit_closed_at') THEN
    ALTER TABLE reservations ADD COLUMN deposit_closed_at DATETIME NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='dining_payments' AND column_name='deposit_applied') THEN
    ALTER TABLE dining_payments ADD COLUMN deposit_applied DECIMAL(16,0) NOT NULL DEFAULT 0;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='reservation_emails' AND column_name='deposit_summary') THEN
    ALTER TABLE reservation_emails ADD COLUMN deposit_summary VARCHAR(400) NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='reservations' AND index_name='ix_reservation_deposit_deadline') THEN
    ALTER TABLE reservations ADD INDEX ix_reservation_deposit_deadline
      (status, deposit_status, deposit_due_at, deposit_review_due_at);
  END IF;

  UPDATE reservations
    SET deposit_paid=deposit_amount WHERE deposit_status='PAID' AND deposit_paid=0;
  -- Old paid invoices charged the full total. Preserve the booked amounts and avoid applying old deposits again.
  UPDATE reservations r
    LEFT JOIN dining_orders o ON o.reservation_id=r.id
    LEFT JOIN dining_payments p ON p.order_id=o.id
    SET r.deposit_status='APPLIED', r.deposit_closed_at=COALESCE(p.paid_at,r.completed_at,r.created_at)
    WHERE r.deposit_status='PAID' AND p.id IS NOT NULL AND r.deposit_closed_at IS NULL;
  UPDATE reservations
    SET deposit_status='CLOSED', deposit_closed_at=COALESCE(completed_at,created_at)
    WHERE deposit_status='PAID' AND status IN ('COMPLETED','CANCELLED','NO_SHOW') AND deposit_closed_at IS NULL;

  UPDATE reservations
    SET deposit_due_at=LEAST(TIMESTAMP(reservation_date,reservation_time), DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 120 MINUTE))
    WHERE status='CONFIRMED' AND deposit_paid < deposit_amount AND deposit_due_at IS NULL
      AND deposit_status IN ('WAITING_PAYMENT','TRANSFER_SUBMITTED');
  UPDATE reservations
    SET deposit_submitted_amount=deposit_amount-deposit_paid, deposit_review_due_at=deposit_due_at
    WHERE deposit_status='TRANSFER_SUBMITTED' AND status='CONFIRMED' AND deposit_submitted_amount IS NULL;
  UPDATE reservations
    SET deposit_status='CLOSED_TRANSFER_PENDING', deposit_closed_at=COALESCE(completed_at,created_at),
      deposit_submitted_amount=deposit_amount-deposit_paid
    WHERE deposit_status='TRANSFER_SUBMITTED' AND status IN ('CANCELLED','NO_SHOW') AND deposit_submitted_amount IS NULL;

  -- Prefer the earlier invoice claim if legacy records already reused a code; all original rows remain intact.
  INSERT IGNORE INTO payment_reference_claims(reference_code,purpose,record_id)
    SELECT UPPER(REGEXP_REPLACE(TRIM(transfer_reference),'[[:space:]]+',' ')), 'INVOICE', order_id
    FROM dining_payments WHERE transfer_reference IS NOT NULL AND TRIM(transfer_reference)<>''
    ORDER BY id;
  INSERT IGNORE INTO payment_reference_claims(reference_code,purpose,record_id)
    SELECT UPPER(REGEXP_REPLACE(TRIM(deposit_reference),'[[:space:]]+',' ')), 'DEPOSIT', id
    FROM reservations WHERE deposit_paid>0 AND deposit_reference IS NOT NULL AND TRIM(deposit_reference)<>''
      AND deposit_status NOT IN ('TRANSFER_SUBMITTED','CLOSED_TRANSFER_PENDING')
    ORDER BY id;
END //
DELIMITER ;
CALL t3l_migrate_deposit_settlement();
DROP PROCEDURE t3l_migrate_deposit_settlement;
