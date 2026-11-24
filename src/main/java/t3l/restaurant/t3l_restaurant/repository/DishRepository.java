package t3l.restaurant.t3l_restaurant.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import t3l.restaurant.t3l_restaurant.entity.Dish;

public interface DishRepository extends JpaRepository<Dish, Long> {
  String SELLABLE = "d.available = true and d.published = true and not exists "
      + "(select s.id from RestaurantSection s where s.kind = 'MENU' and s.name = d.categoryName and s.active = false)";

  @Query("select d from Dish d where " + SELLABLE + " order by d.categoryName, d.name")
  List<Dish> findByAvailableTrueOrderByCategoryNameAscNameAsc();

  @Query("select d from Dish d where d.id = :id and " + SELLABLE)
  Optional<Dish> findSellable(@Param("id") Long id);

  @Query("select d from Dish d where d.id in :ids and " + SELLABLE)
  List<Dish> findSellableByIdIn(@Param("ids") java.util.Collection<Long> ids);

  @Query("select distinct d.categoryName from Dish d order by d.categoryName")
  List<String> categoryNames();

  @Modifying
  @Query("update Dish d set d.categoryName = :next where d.categoryName = :previous")
  int renameCategory(@Param("previous") String previous, @Param("next") String next);

  @Query("""
      select d from Dish d where (:category = '' or d.categoryName = :category)
        and (:available is null or d.available = :available) and (:published is null or d.published = :published)
        and (lower(d.name) like :text escape '!' or lower(d.description) like :text escape '!')
      """)
  Page<Dish> search(@Param("text") String text, @Param("category") String category,
      @Param("available") Boolean available, @Param("published") Boolean published, Pageable pageable);

  java.util.Optional<Dish> findByName(String name);
}
