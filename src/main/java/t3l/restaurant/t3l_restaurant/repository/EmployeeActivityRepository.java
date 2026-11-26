package t3l.restaurant.t3l_restaurant.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import t3l.restaurant.t3l_restaurant.entity.EmployeeActivity;

public interface EmployeeActivityRepository extends JpaRepository<EmployeeActivity, Long> {
  interface HistoryEntry {
    EmployeeActivity getActivity();
    Long getReservationId();
    String getReservationCode();
    Long getShiftId();
    Long getOwnShiftId();
    String getTableCode();
  }

  @Query("select distinct a.operation from EmployeeActivity a where a.actor = :actor order by a.operation")
  List<String> findOperationsByActor(String actor);

  @Query("""
      select a as activity, r.id as reservationId,
        case when a.targetType = 'RESERVATION' then a.targetLabel else r.code end as reservationCode,
        shift.id as shiftId,
        case when shiftOwner.username = :actor then shift.id else null end as ownShiftId,
        tableRef.code as tableCode
      from EmployeeActivity a
      left join DiningOrder bill on a.targetType = 'DINING_ORDER' and bill.id = a.targetId
      left join DiningOrderItem line on a.targetType = 'DINING_ORDER_ITEM' and line.id = a.targetId
      left join line.order itemBill
      left join Reservation r on
        (a.targetType = 'RESERVATION' and r.id = a.targetId)
        or r.id = bill.reservation.id or r.id = itemBill.reservation.id
        or (a.targetType = 'GENERAL' and a.operation = 'Xét duyệt yêu cầu'
          and locate(' · ', a.targetLabel) > 0
          and r.code = substring(a.targetLabel, 1, locate(' · ', a.targetLabel) - 1))
      left join CashSale sale on a.targetType = 'CASH_SALE' and sale.id = a.targetId
      left join StaffShift shift on (a.targetType = 'SHIFT' and shift.id = a.targetId) or shift.id = sale.shift.id
      left join shift.employee shiftOwner
      left join RestaurantTable tableRef on a.targetType = 'TABLE' and tableRef.id = a.targetId
      where a.actor = :actor
        and (:fromTime is null or a.occurredAt >= :fromTime)
        and (:untilTime is null or a.occurredAt < :untilTime)
        and (:operation = '' or a.operation = :operation)
        and (:code = '' or locate(:code, lower(r.code)) > 0
          or (a.targetType = 'RESERVATION' and locate(:code, lower(a.targetLabel)) > 0)
          or (a.targetType = 'GENERAL' and a.operation = 'Xét duyệt yêu cầu'
            and locate(' · ', a.targetLabel) > 0
            and locate(:code, lower(substring(a.targetLabel, 1, locate(' · ', a.targetLabel) - 1))) > 0))
      order by a.occurredAt desc, a.id desc
      """)
  Page<HistoryEntry> findHistory(String actor, String code, String operation,
      LocalDateTime fromTime, LocalDateTime untilTime, Pageable pageable);
}
