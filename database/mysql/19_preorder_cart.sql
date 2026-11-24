-- Preserve existing order snapshots and identify legacy preorder lines still awaiting service.
USE t3l_restaurant;
DROP PROCEDURE IF EXISTS t3l_add_preorder_cart;
DELIMITER //
CREATE PROCEDURE t3l_add_preorder_cart()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='dining_order_items' AND column_name='note') THEN
    ALTER TABLE dining_order_items ADD COLUMN note VARCHAR(120) NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='dining_order_items' AND column_name='preordered') THEN
    ALTER TABLE dining_order_items ADD COLUMN preordered BOOLEAN NOT NULL DEFAULT FALSE;
    UPDATE dining_order_items i
      JOIN dining_orders o ON o.id=i.order_id
      JOIN reservations r ON r.id=o.reservation_id
      SET i.preordered=TRUE
      WHERE o.opened_by='CUSTOMER' AND r.seated_at IS NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='dining_orders' AND column_name='preorder_note') THEN
    ALTER TABLE dining_orders ADD COLUMN preorder_note VARCHAR(500) NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='reservation_change_requests' AND column_name='preorder_payload') THEN
    ALTER TABLE reservation_change_requests ADD COLUMN preorder_payload TEXT NULL;
  END IF;
END //
DELIMITER ;
CALL t3l_add_preorder_cart();
DROP PROCEDURE t3l_add_preorder_cart;
