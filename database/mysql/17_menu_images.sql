-- Liên kết ảnh Cloudinary công khai cho danh mục 54 món từ 04_menu_catalog_seed.sql.
USE t3l_restaurant;
SET NAMES utf8mb4;

UPDATE dishes AS dish
JOIN (
  SELECT '7Up' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676916/t3l-restaurant/menu/nuoc_7up.png' AS image_url, 't3l-restaurant/menu/nuoc_7up' AS image_public_id
  UNION ALL
  SELECT 'Bánh cuốn tôm thịt' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1795457742/t3l-restaurant/menu/optimized/BanhCuonTomThit.webp' AS image_url, 't3l-restaurant/menu/optimized/BanhCuonTomThit' AS image_public_id
  UNION ALL
  SELECT 'Bánh mousse chanh dây' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676838/t3l-restaurant/menu/mon_banhmoussechanhday.png' AS image_url, 't3l-restaurant/menu/mon_banhmoussechanhday' AS image_public_id
  UNION ALL
  SELECT 'Bánh quy sô-cô-la' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676842/t3l-restaurant/menu/mon_banhquysocola.png' AS image_url, 't3l-restaurant/menu/mon_banhquysocola' AS image_public_id
  UNION ALL
  SELECT 'Bia Heineken' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676918/t3l-restaurant/menu/nuoc_biaheneken.png' AS image_url, 't3l-restaurant/menu/nuoc_biaheneken' AS image_public_id
  UNION ALL
  SELECT 'Bia Tiger bạc' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676920/t3l-restaurant/menu/nuoc_biatigerbac.png' AS image_url, 't3l-restaurant/menu/nuoc_biatigerbac' AS image_public_id
  UNION ALL
  SELECT 'Bia Tiger nâu' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676922/t3l-restaurant/menu/nuoc_biatigernau.png' AS image_url, 't3l-restaurant/menu/nuoc_biatigernau' AS image_public_id
  UNION ALL
  SELECT 'Bò bít tết sốt tiêu xanh' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676846/t3l-restaurant/menu/mon_bobittet.png' AS image_url, 't3l-restaurant/menu/mon_bobittet' AS image_public_id
  UNION ALL
  SELECT 'Bò cuốn lá lốt' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676848/t3l-restaurant/menu/mon_bocuonlalot.png' AS image_url, 't3l-restaurant/menu/mon_bocuonlalot' AS image_public_id
  UNION ALL
  SELECT 'Bò cuốn rau củ' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676850/t3l-restaurant/menu/mon_bocuonraucu.png' AS image_url, 't3l-restaurant/menu/mon_bocuonraucu' AS image_public_id
  UNION ALL
  SELECT 'Bò lúc lắc khoai tây' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676852/t3l-restaurant/menu/mon_boluclac.png' AS image_url, 't3l-restaurant/menu/mon_boluclac' AS image_public_id
  UNION ALL
  SELECT 'Bò nướng tảng sốt tiêu xanh' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676854/t3l-restaurant/menu/mon_bonuongtang.png' AS image_url, 't3l-restaurant/menu/mon_bonuongtang' AS image_public_id
  UNION ALL
  SELECT 'Burger bò phô mai' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676887/t3l-restaurant/menu/mon_hamburger.png' AS image_url, 't3l-restaurant/menu/mon_hamburger' AS image_public_id
  UNION ALL
  SELECT 'Cá hồi áp chảo' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676857/t3l-restaurant/menu/mon_cahoiraucu.png' AS image_url, 't3l-restaurant/menu/mon_cahoiraucu' AS image_public_id
  UNION ALL
  SELECT 'Chả mực Hạ Long' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1795457744/t3l-restaurant/menu/optimized/ChaMucHaLong.webp' AS image_url, 't3l-restaurant/menu/optimized/ChaMucHaLong' AS image_public_id
  UNION ALL
  SELECT 'Chả ram tôm đất' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1795457757/t3l-restaurant/menu/optimized/ChaRamTomDat.webp' AS image_url, 't3l-restaurant/menu/optimized/ChaRamTomDat' AS image_public_id
  UNION ALL
  SELECT 'Chạo tôm mía' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1795457747/t3l-restaurant/menu/optimized/ChaoTomMia.webp' AS image_url, 't3l-restaurant/menu/optimized/ChaoTomMia' AS image_public_id
  UNION ALL
  SELECT 'Chè khúc bạch' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676865/t3l-restaurant/menu/mon_chekhucbach.png' AS image_url, 't3l-restaurant/menu/mon_chekhucbach' AS image_public_id
  UNION ALL
  SELECT 'Chè sen long nhãn' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676867/t3l-restaurant/menu/mon_chelongnhan.png' AS image_url, 't3l-restaurant/menu/mon_chelongnhan' AS image_public_id
  UNION ALL
  SELECT 'Coca-Cola' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676923/t3l-restaurant/menu/nuoc_coca.png' AS image_url, 't3l-restaurant/menu/nuoc_coca' AS image_public_id
  UNION ALL
  SELECT 'Cơm chiên hải sản' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676869/t3l-restaurant/menu/mon_comchien.png' AS image_url, 't3l-restaurant/menu/mon_comchien' AS image_public_id
  UNION ALL
  SELECT 'Cupcake mâm xôi' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676870/t3l-restaurant/menu/mon_cupcakemamxoi.png' AS image_url, 't3l-restaurant/menu/mon_cupcakemamxoi' AS image_public_id
  UNION ALL
  SELECT 'Cừu hầm rượu vang' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676872/t3l-restaurant/menu/mon_cuuhamruouvang.png' AS image_url, 't3l-restaurant/menu/mon_cuuhamruouvang' AS image_public_id
  UNION ALL
  SELECT 'Gà hầm sâm' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676874/t3l-restaurant/menu/mon_gahamsam.png' AS image_url, 't3l-restaurant/menu/mon_gahamsam' AS image_public_id
  UNION ALL
  SELECT 'Gà nướng mật ong' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676878/t3l-restaurant/menu/mon_ganuongmatong.png' AS image_url, 't3l-restaurant/menu/mon_ganuongmatong' AS image_public_id
  UNION ALL
  SELECT 'Gan ngỗng áp chảo' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676876/t3l-restaurant/menu/mon_ganngong.png' AS image_url, 't3l-restaurant/menu/mon_ganngong' AS image_public_id
  UNION ALL
  SELECT 'Gỏi bò bóp thấu' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1795457760/t3l-restaurant/menu/optimized/GoiBoBopThau.webp' AS image_url, 't3l-restaurant/menu/optimized/GoiBoBopThau' AS image_public_id
  UNION ALL
  SELECT 'Gỏi bông ngũ sắc' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1795457763/t3l-restaurant/menu/optimized/GoiBongNguSac.webp' AS image_url, 't3l-restaurant/menu/optimized/GoiBongNguSac' AS image_public_id
  UNION ALL
  SELECT 'Gỏi ngó sen tôm thịt' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1795457765/t3l-restaurant/menu/optimized/GoiNgoSenTomThit.webp' AS image_url, 't3l-restaurant/menu/optimized/GoiNgoSenTomThit' AS image_public_id
  UNION ALL
  SELECT 'Kem flan' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676889/t3l-restaurant/menu/mon_kemplan.png' AS image_url, 't3l-restaurant/menu/mon_kemplan' AS image_public_id
  UNION ALL
  SELECT 'Kem trái cây' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676891/t3l-restaurant/menu/mon_kemtraicay.png' AS image_url, 't3l-restaurant/menu/mon_kemtraicay' AS image_public_id
  UNION ALL
  SELECT 'Khoai lang chiên' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1795457767/t3l-restaurant/menu/optimized/KhoaiLangChien.webp' AS image_url, 't3l-restaurant/menu/optimized/KhoaiLangChien' AS image_public_id
  UNION ALL
  SELECT 'Lẩu gà bò nấm' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676895/t3l-restaurant/menu/mon_lagubo.png' AS image_url, 't3l-restaurant/menu/mon_lagubo' AS image_public_id
  UNION ALL
  SELECT 'Mì ống penne sốt kem' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676897/t3l-restaurant/menu/mon_miongpenne.png' AS image_url, 't3l-restaurant/menu/mon_miongpenne' AS image_public_id
  UNION ALL
  SELECT 'Mì ramen' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676899/t3l-restaurant/menu/mon_miramen.png' AS image_url, 't3l-restaurant/menu/mon_miramen' AS image_public_id
  UNION ALL
  SELECT 'Mì xào bò' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676901/t3l-restaurant/menu/mon_mixao.png' AS image_url, 't3l-restaurant/menu/mon_mixao' AS image_public_id
  UNION ALL
  SELECT 'Mì Ý spaghetti bò bằm' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676903/t3l-restaurant/menu/mon_miyspageti.png' AS image_url, 't3l-restaurant/menu/mon_miyspageti' AS image_public_id
  UNION ALL
  SELECT 'Mirinda cam' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676928/t3l-restaurant/menu/nuoc_mirindacam.png' AS image_url, 't3l-restaurant/menu/nuoc_mirindacam' AS image_public_id
  UNION ALL
  SELECT 'Nấm xào hương thảo' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1795457768/t3l-restaurant/menu/optimized/NamXaoHuongThao.webp' AS image_url, 't3l-restaurant/menu/optimized/NamXaoHuongThao' AS image_public_id
  UNION ALL
  SELECT 'Nước khoáng Aquafina' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676925/t3l-restaurant/menu/nuoc_locaquafina.png' AS image_url, 't3l-restaurant/menu/nuoc_locaquafina' AS image_public_id
  UNION ALL
  SELECT 'Nước khoáng Satori' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676927/t3l-restaurant/menu/nuoc_locsatori.png' AS image_url, 't3l-restaurant/menu/nuoc_locsatori' AS image_public_id
  UNION ALL
  SELECT 'Pancake trái cây' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676840/t3l-restaurant/menu/mon_banhpancake.png' AS image_url, 't3l-restaurant/menu/mon_banhpancake' AS image_public_id
  UNION ALL
  SELECT 'Panna cotta' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676907/t3l-restaurant/menu/mon_panacota.png' AS image_url, 't3l-restaurant/menu/mon_panacota' AS image_public_id
  UNION ALL
  SELECT 'Pepsi' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676930/t3l-restaurant/menu/nuoc_pepsi.png' AS image_url, 't3l-restaurant/menu/nuoc_pepsi' AS image_public_id
  UNION ALL
  SELECT 'Red Bull' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676932/t3l-restaurant/menu/nuoc_redbull.png' AS image_url, 't3l-restaurant/menu/nuoc_redbull' AS image_public_id
  UNION ALL
  SELECT 'Sprite' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676934/t3l-restaurant/menu/nuoc_sprite.png' AS image_url, 't3l-restaurant/menu/nuoc_sprite' AS image_public_id
  UNION ALL
  SELECT 'Sting' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676936/t3l-restaurant/menu/nuoc_sting.png' AS image_url, 't3l-restaurant/menu/nuoc_sting' AS image_public_id
  UNION ALL
  SELECT 'Súp cua thịt bắp' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1795457770/t3l-restaurant/menu/optimized/SupCuaThitBap.webp' AS image_url, 't3l-restaurant/menu/optimized/SupCuaThitBap' AS image_public_id
  UNION ALL
  SELECT 'Súp cua tổ yến' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1795457774/t3l-restaurant/menu/optimized/SupCuaToYen.webp' AS image_url, 't3l-restaurant/menu/optimized/SupCuaToYen' AS image_public_id
  UNION ALL
  SELECT 'Súp cua tuyết nhĩ' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1795457776/t3l-restaurant/menu/optimized/SupCuaTuyetNhi.webp' AS image_url, 't3l-restaurant/menu/optimized/SupCuaTuyetNhi' AS image_public_id
  UNION ALL
  SELECT 'Tiramisu cà phê' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676844/t3l-restaurant/menu/mon_banhtirramisu.png' AS image_url, 't3l-restaurant/menu/mon_banhtirramisu' AS image_public_id
  UNION ALL
  SELECT 'Tôm chiên xù' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1795457781/t3l-restaurant/menu/optimized/TomChienXu.webp' AS image_url, 't3l-restaurant/menu/optimized/TomChienXu' AS image_public_id
  UNION ALL
  SELECT 'Warrior vị dâu' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676938/t3l-restaurant/menu/nuoc_warriordau.png' AS image_url, 't3l-restaurant/menu/nuoc_warriordau' AS image_public_id
  UNION ALL
  SELECT 'Warrior vị nho' AS name, 'https://res.cloudinary.com/q1tvtgmx/image/upload/v1794676940/t3l-restaurant/menu/nuoc_warriornho.png' AS image_url, 't3l-restaurant/menu/nuoc_warriornho' AS image_public_id
) AS catalog ON catalog.name = dish.name
SET dish.image_url = catalog.image_url, dish.image_public_id = catalog.image_public_id
WHERE dish.image_url IS NULL OR dish.image_url = '';
