package t3l.restaurant.t3l_restaurant.repository;

import java.util.List;
import java.util.HashSet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import t3l.restaurant.t3l_restaurant.entity.RestaurantTable;

public interface RestaurantTableRepository extends JpaRepository<RestaurantTable, Long> {
  String ACTIVE = "t.active = true and not exists "
      + "(select s.id from RestaurantSection s where s.kind = 'AREA' and s.name = t.tableType and s.active = false)";

  @Query("select t from RestaurantTable t where " + ACTIVE + " order by t.code")
  List<RestaurantTable> findByActiveTrueOrderByCodeAsc();
  List<RestaurantTable> findAllByOrderByTableTypeAscCodeAsc();

  // All allocation/status writers acquire these rows in the same order, until commit.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from RestaurantTable t order by t.id")
  List<RestaurantTable> lockInventory();

  @Query("select t.id from RestaurantTable t where " + ACTIVE)
  List<Long> activeIds();

  default List<RestaurantTable> lockActiveTables() {
    var inventory = lockInventory();
    var ids = new HashSet<>(activeIds());
    return inventory.stream().filter(table -> ids.contains(table.getId())).toList();
  }

  @Modifying
  @Query("update RestaurantTable t set t.tableType = :next where t.tableType = :previous")
  int renameArea(@Param("previous") String previous, @Param("next") String next);
}
