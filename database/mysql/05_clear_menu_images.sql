-- Gỡ ảnh khỏi thực đơn trong giai đoạn chuẩn bị lại hình ảnh.
-- Có thể chạy lặp lại an toàn; các file nguồn trong img không bị tác động.
USE t3l_restaurant;
SET NAMES utf8mb4;

UPDATE dishes
SET image_url = NULL,
    image_public_id = NULL
WHERE image_url IS NOT NULL
   OR image_public_id IS NOT NULL;
