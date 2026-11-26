package t3l.restaurant.t3l_restaurant.entity;

import java.util.Locale;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TableKind {
  SMALL("Bàn nhỏ", 4),
  MEDIUM("Bàn vừa", 8),
  LARGE("Bàn lớn", 15),
  VIP("Phòng VIP", TableKind.MAX_CAPACITY);

  public static final int MAX_CAPACITY = 30;
  private final String label;
  private final int fixedCapacity;

  public static TableKind fromInventory(String kind, String legacyType, int capacity) {
    for (TableKind option : values()) {
      if (option.name().equalsIgnoreCase(kind) || option.label.equalsIgnoreCase(kind)) return option;
    }
    String type = legacyType == null ? "" : legacyType.toLowerCase(Locale.ROOT);
    if (type.contains("vip")) return VIP;
    if (type.contains("lớn")) return LARGE;
    if (type.contains("vừa")) return MEDIUM;
    return capacity <= 4 ? SMALL : capacity <= 8 ? MEDIUM : LARGE;
  }
}
