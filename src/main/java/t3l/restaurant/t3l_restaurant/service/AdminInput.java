package t3l.restaurant.t3l_restaurant.service;

import java.net.URI;
import java.util.Locale;

public final class AdminInput {
  private AdminInput() {}

  public static String like(String text) {
    return "%" + text.strip().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  public static String optional(String text) { return text == null || text.isBlank() ? null : text.strip(); }

  public static void validateImage(String text) {
    String value = optional(text);
    if (value == null) return;
    try {
      URI uri = URI.create(value);
      if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null) return;
    } catch (IllegalArgumentException ignored) {}
    throw new IllegalArgumentException("Đường dẫn ảnh phải là URL HTTPS công khai hợp lệ.");
  }
}
