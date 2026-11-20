package t3l.restaurant.t3l_restaurant.security;

import java.net.URI;
import java.util.Set;

public final class LoginReturnTarget {
  private static final Set<String> PAGES = Set.of("/", "/menu", "/about", "/contact", "/faq",
      "/reservations/new", "/reservations/lookup", "/reservations/confirmation");

  private LoginReturnTarget() {}

  public static String validate(String target) {
    if (target == null || target.length() > 2048 || !target.startsWith("/") || target.startsWith("//")) return null;
    try {
      URI uri = URI.create(target);
      String path = uri.getRawPath();
      if (uri.isAbsolute() || uri.getRawAuthority() != null || path == null || !PAGES.contains(path)) return null;
      return uri.toASCIIString();
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }
}
