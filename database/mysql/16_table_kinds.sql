-- Chuẩn hóa loại bàn mà không thay đổi mã bàn, đặt bàn cũ hoặc sức chứa phòng VIP.
USE t3l_restaurant;
SET NAMES utf8mb4;

START TRANSACTION;

UPDATE restaurant_tables
SET table_kind = CASE
  WHEN UPPER(COALESCE(table_kind, '')) IN ('SMALL','MEDIUM','LARGE','VIP') THEN UPPER(table_kind)
  WHEN table_kind = 'Phòng VIP' THEN 'VIP'
  WHEN table_kind = 'Bàn lớn' THEN 'LARGE'
  WHEN table_kind = 'Bàn vừa' THEN 'MEDIUM'
  WHEN table_kind = 'Bàn nhỏ' THEN 'SMALL'
  WHEN LOWER(table_type) LIKE '%vip%' THEN 'VIP'
  WHEN LOWER(table_type) LIKE '%lớn%' THEN 'LARGE'
  WHEN LOWER(table_type) LIKE '%vừa%' THEN 'MEDIUM'
  WHEN capacity > 8 THEN 'LARGE'
  WHEN capacity > 4 THEN 'MEDIUM'
  ELSE 'SMALL'
END;

-- Không hạ sức chứa của bàn cũ có cấu hình riêng; admin có thể đổi sau khi xử lý các lượt đặt.
UPDATE restaurant_tables
SET capacity = CASE table_kind
  WHEN 'SMALL' THEN GREATEST(capacity, 4)
  WHEN 'MEDIUM' THEN GREATEST(capacity, 8)
  WHEN 'LARGE' THEN GREATEST(capacity, 16)
  ELSE capacity
END
WHERE table_kind IN ('SMALL','MEDIUM','LARGE');

COMMIT;
