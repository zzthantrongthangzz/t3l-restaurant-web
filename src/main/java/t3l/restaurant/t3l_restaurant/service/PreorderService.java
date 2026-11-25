package t3l.restaurant.t3l_restaurant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import t3l.restaurant.t3l_restaurant.dto.PreorderForm;
import t3l.restaurant.t3l_restaurant.dto.ReservationForm;
import t3l.restaurant.t3l_restaurant.entity.DiningOrder;
import t3l.restaurant.t3l_restaurant.entity.DiningOrderItem;
import t3l.restaurant.t3l_restaurant.entity.Dish;
import t3l.restaurant.t3l_restaurant.entity.Reservation;
import t3l.restaurant.t3l_restaurant.repository.DiningOrderRepository;
import t3l.restaurant.t3l_restaurant.repository.DishRepository;

@Service
@RequiredArgsConstructor
public class PreorderService {
  private final DishRepository dishes;
  private final DiningOrderRepository orders;
  private final ObjectMapper json;
  private final VoucherService vouchers;

  public record Line(Long dishId, String name, BigDecimal price, int quantity) {
    public BigDecimal amount() { return price.multiply(BigDecimal.valueOf(quantity)); }
  }

  public record Quote(List<Line> lines, String note, String voucherCode) {
    public Quote(List<Line> lines, String note) { this(lines,note,null); }
    public BigDecimal total() { return lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add); }
    public Map<Long, Integer> quantities() {
      var quantities = new TreeMap<Long, Integer>();
      lines.forEach(line -> quantities.merge(line.dishId(), line.quantity(), Integer::sum));
      return quantities;
    }
  }

  public record Card(Long id, String name, String category, String image, BigDecimal price, int quantity,
                     boolean available, List<Line> existing) {}

  public Map<Long, Integer> selection(PreorderForm form) {
    var counts = new TreeMap<Long, Integer>();
    if (form.getPreorderQuantities() != null) {
      form.getPreorderQuantities().forEach((id, quantity) -> {
        if (id == null || id < 1 || quantity == null || quantity < 1 || quantity > PreorderForm.MAX_QUANTITY) {
          throw new BookingException("preorderQuantities", "Số lượng mỗi món phải từ 1 đến 20.");
        }
        counts.put(id, quantity);
      });
    }
    if (form instanceof ReservationForm reservation && reservation.getPreorderDishIds() != null) {
      for (Long id : reservation.getPreorderDishIds()) {
        if (id == null) continue;
        if (id < 1) throw new BookingException("preorderQuantities", "Món đặt trước không hợp lệ.");
        counts.putIfAbsent(id, 1);
      }
    }
    if (counts.size() > PreorderForm.MAX_DISHES) {
      throw new BookingException("preorderQuantities", "Chỉ có thể chọn tối đa 30 loại món.");
    }
    return counts;
  }

  public Quote quote(PreorderForm form, DiningOrder order) {
    var quantities = selection(form);
    Quote quote = quote(quantities, form.getPreorderNote(), order);
    if (form.getPreorderExpectedTotal() != null && form.getPreorderExpectedTotal().compareTo(quote.total()) != 0) {
      throw new BookingException("preorderQuantities", "Giá món đã thay đổi. Vui lòng xem lại giỏ món và xác nhận tổng mới.");
    }
    return new Quote(quote.lines(),quote.note(),form.getVoucherCode());
  }

  Quote quote(Map<Long, Integer> quantities, String note, DiningOrder order) {
    var current = current(order);
    var catalog = sellable(quantities.keySet());
    var lines = new ArrayList<Line>();
    for (var entry : quantities.entrySet()) {
      var dish = catalog.get(entry.getKey());
      if (dish == null) {
        throw new BookingException("preorderQuantities", "Có món đã hết hoặc ngừng bán. Vui lòng bỏ món đó và thử lại.");
      }
      int remaining = entry.getValue();
      for (Line old : current.lines()) {
        if (!old.dishId().equals(entry.getKey()) || remaining == 0) continue;
        int kept = Math.min(remaining, old.quantity());
        append(lines, new Line(old.dishId(), old.name(), old.price(), kept));
        remaining -= kept;
      }
      if (remaining > 0) append(lines, new Line(dish.getId(), dish.getName(), dish.getPrice(), remaining));
    }
    String normalized = lines.isEmpty() || note == null || note.isBlank() ? null : note.strip();
    return new Quote(List.copyOf(lines), normalized);
  }

  public Quote current(DiningOrder order) {
    if (order == null) return new Quote(List.of(), null);
    var lines = new ArrayList<Line>();
    order.getPreorderItems().stream()
        .map(item -> new Line(item.getDishId(), item.getDishName(), item.getUnitPrice(), item.getQuantity()))
        .sorted(java.util.Comparator.comparing(Line::dishId)).forEach(line -> append(lines, line));
    return new Quote(List.copyOf(lines), order.getPreorderNote(),order.getVoucherCode());
  }

  private void append(List<Line> lines, Line next) {
    if (!lines.isEmpty()) {
      var last = lines.getLast();
      if (last.dishId().equals(next.dishId()) && last.name().equals(next.name())
          && last.price().compareTo(next.price()) == 0) {
        lines.set(lines.size() - 1, new Line(last.dishId(), last.name(), last.price(), last.quantity() + next.quantity()));
        return;
      }
    }
    lines.add(next);
  }

  public List<Card> picker(Map<Long, Integer> counts, DiningOrder order) {
    var existing = current(order).lines().stream().collect(Collectors.groupingBy(Line::dishId));
    var catalog = dishes.findByAvailableTrueOrderByCategoryNameAscNameAsc();
    var live = catalog.stream().map(Dish::getId).collect(Collectors.toSet());
    var cards = new ArrayList<Card>();
    for (Dish dish : catalog) {
      cards.add(new Card(dish.getId(), dish.getName(), dish.getCategoryName(), dish.getImageUrl(), dish.getPrice(),
          counts.getOrDefault(dish.getId(), 0), true, existing.getOrDefault(dish.getId(), List.of())));
    }
    counts.forEach((id, quantity) -> {
      if (live.contains(id)) return;
      var old = existing.getOrDefault(id, List.of());
      cards.add(new Card(id, old.isEmpty() ? "Món không còn phục vụ" : old.getFirst().name(),
          "Không còn bán", null, BigDecimal.ZERO, quantity, false, old));
    });
    return cards;
  }

  public List<Card> picker(PreorderForm form, DiningOrder order) {
    try {
      return picker(selection(form), order);
    } catch (BookingException exception) {
      return picker(current(order).quantities(), order);
    }
  }

  public DiningOrder order(Long bookingId) { return orders.findByReservation_Id(bookingId).orElse(null); }

  DiningOrder apply(Reservation booking, DiningOrder order, Quote quote, LocalDateTime now) {
    if (order == null && quote.lines().isEmpty()) {
      if (quote.voucherCode()!=null && !quote.voucherCode().isBlank()) throw new BookingException("voucherCode","Hãy chọn món trước khi áp dụng voucher.");
      return null;
    }
    if (order == null) order = new DiningOrder(booking, "CUSTOMER", now);
    if (!DiningOrder.DRAFT.equals(order.getStatus())) throw new BookingException(null, "Đơn món đã đóng hoặc đã chốt.");
    var previous = new ArrayList<>(order.getPreorderItems());
    for (Line line : quote.lines()) {
      var kept = previous.stream().filter(item -> item.getDishId().equals(line.dishId())
          && item.getDishName().equals(line.name()) && item.getUnitPrice().compareTo(line.price()) == 0
          && item.getQuantity() == line.quantity()).findFirst();
      if (kept.isPresent()) previous.remove(kept.get());
      else order.addPreorderItem(line.dishId(), line.name(), line.price(), line.quantity());
    }
    previous.forEach(DiningOrderItem::cancel);
    order.setPreorderNote(quote.note());
    order=orders.save(order);
    if (quote.voucherCode()!=null) vouchers.change(order,booking,quote.voucherCode(),quote.total());
    return order;
  }

  public Set<Long> unavailable(DiningOrder order) {
    var ids = current(order).quantities().keySet();
    var live = sellable(ids);
    return ids.stream().filter(id -> !live.containsKey(id)).collect(Collectors.toUnmodifiableSet());
  }

  List<String> removeUnavailable(DiningOrder order) {
    if (order == null || !DiningOrder.DRAFT.equals(order.getStatus())) return List.of();
    var unavailable = unavailable(order);
    return order.getPreorderItems().stream().filter(item -> unavailable.contains(item.getDishId())).map(item -> {
      item.cancel();
      return item.getDishName();
    }).distinct().toList();
  }

  void close(Long bookingId) {
    var order = order(bookingId);
    if (order != null && DiningOrder.DRAFT.equals(order.getStatus())) {
      vouchers.release(order);
      order.voidEmptyDraft();
    }
  }

  String encode(Quote quote) {
    try {
      return json.writeValueAsString(quote);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Không thể lưu yêu cầu đổi món.", exception);
    }
  }

  public Quote decode(String payload) {
    if (payload == null) return null;
    try {
      return json.readValue(payload, Quote.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Không thể đọc yêu cầu đổi món.", exception);
    }
  }

  private Map<Long, Dish> sellable(Set<Long> ids) {
    return ids.isEmpty() ? Map.of() : dishes.findSellableByIdIn(ids).stream()
        .collect(Collectors.toMap(Dish::getId, Function.identity()));
  }
}
