-- Keep these values in sync with ReservationEmail.Kind and ReservationEmail.Status.
-- Hibernate generated numbered CHECK names on some existing MySQL schemas.
-- Match only the legacy single-column enum checks and keep unrelated checks intact.
SELECT
  GROUP_CONCAT(CASE
    WHEN REGEXP_LIKE(tc.CONSTRAINT_NAME, '^reservation_emails_chk_[0-9]+$', 'c')
      AND REGEXP_LIKE(
        REGEXP_REPLACE(REPLACE(REPLACE(cc.CHECK_CLAUSE, CHAR(96), ''), CHAR(92), ''), '[[:space:]]+', ''),
        '^[(]?(kind|status)in[(](_[[:alnum:]]+)?''[A-Z_]+''(,(_[[:alnum:]]+)?''[A-Z_]+'')*[)][)]?$',
        'c'
      )
    THEN CONCAT('DROP CHECK `', tc.CONSTRAINT_NAME, '`') END ORDER BY tc.CONSTRAINT_NAME SEPARATOR ', '),
  COUNT(CASE WHEN tc.CONSTRAINT_NAME = 'chk_reservation_email_kind' THEN 1 END),
  COUNT(CASE WHEN tc.CONSTRAINT_NAME = 'chk_reservation_email_status' THEN 1 END)
INTO @t3l_email_legacy_checks, @t3l_email_kind_exists, @t3l_email_status_exists
FROM information_schema.TABLE_CONSTRAINTS tc
JOIN information_schema.CHECK_CONSTRAINTS cc USING (CONSTRAINT_SCHEMA, CONSTRAINT_NAME)
WHERE tc.TABLE_SCHEMA = DATABASE()
  AND tc.TABLE_NAME = 'reservation_emails'
  AND tc.CONSTRAINT_TYPE = 'CHECK';

SET @t3l_email_check_changes = CONCAT_WS(', ',
  @t3l_email_legacy_checks,
  IF(@t3l_email_kind_exists, NULL, 'ADD CONSTRAINT chk_reservation_email_kind CHECK (kind IN (
    ''RECEIVED'', ''CONFIRMED'', ''REJECTED'', ''CANCELLED'', ''RESCHEDULED'',
    ''CANCELLATION_APPROVED'', ''CHANGE_DECLINED'', ''CANCELLATION_DECLINED'', ''NO_SHOW'',
    ''DEPOSIT_VERIFIED'', ''DEPOSIT_REJECTED'', ''DEPOSIT_REFUNDED'', ''DEPOSIT_EXPIRED'', ''DEPOSIT_APPLIED'',
    ''PREORDER_CHANGED'', ''PREORDER_DECLINED'', ''PREORDER_UNAVAILABLE''
  ))'),
  IF(@t3l_email_status_exists, NULL, 'ADD CONSTRAINT chk_reservation_email_status CHECK (status IN (
    ''PENDING'', ''SENDING'', ''RETRY'', ''SENT'', ''FAILED'', ''DELIVERY_UNKNOWN'',
    ''SKIPPED_NO_RECIPIENT'', ''SKIPPED_NO_SMTP''
  ))')
);

-- Replace both legacy checks in one atomic ALTER on the InnoDB table.
SET @t3l_email_check_sql = IF(@t3l_email_check_changes = '', 'DO 0',
  CONCAT('ALTER TABLE reservation_emails ', @t3l_email_check_changes));
PREPARE t3l_email_check_migration FROM @t3l_email_check_sql;
EXECUTE t3l_email_check_migration;
DEALLOCATE PREPARE t3l_email_check_migration;
