package t3l.restaurant.t3l_restaurant.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import t3l.restaurant.t3l_restaurant.entity.RestaurantSettings;

public interface RestaurantSettingsRepository extends JpaRepository<RestaurantSettings, Integer> {}
