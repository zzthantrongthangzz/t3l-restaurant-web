package t3l.restaurant.t3l_restaurant.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;

record ChatIntent(Kind kind, LocalDate date, LocalTime time, Integer guests, BigDecimal budget, String preference) {
  enum Kind { INFO, MENU, AVAILABILITY, DRAFT, LOOKUP, GREETING }
  private static final Pattern CODE = Pattern.compile("(?i)\\bT3L-[A-Z0-9-]{4,16}\\b");
  private static final Pattern EMAIL = Pattern.compile("(?iu)[\\p{L}0-9._%+-]+@[\\p{L}0-9.-]+\\.[\\p{L}]{2,}");
  private static final Pattern PHONE = Pattern.compile("(?<![\\d/-])(?:\\+?84|0)[ .()\\-]*(?:\\d[ .()\\-]*){7,10}\\d(?![\\d/-])|(?<!\\d)\\d{8,}(?!\\d)");
  private static final Pattern PERSONAL = Pattern.compile(
      "(?iu)\\b(?:tôi tên|mình tên|tên (?:của tôi|khách|là)|họ và tên|liên hệ với tôi)(?:\\s*[:=])?\\s+[^\\n,.!?;]{2,80}");
  private static final Pattern PARTY = Pattern.compile("\\b(\\d{1,3})\\s*(?:nguoi|khach|vi|suat)\\b");
  private static final Pattern CLOCK = Pattern.compile("(?<![\\d/])([0-2]?\\d)\\s*(?:h(?:\\s*([0-5]\\d))?|:([0-5]\\d)|gio(?:\\s+([0-5]\\d))?)(?![a-z0-9])");
  private static final Pattern ISO = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
  private static final Pattern DATE = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}))?\\b");
  private static final Pattern BUDGET = Pattern.compile(
      "\\b(\\d{1,9}(?:[,.]\\d{1,3})?)\\s*(k|nghin|ngan|trieu|vnd|dong)\\b|\\b(\\d{1,3}(?:[.,]\\d{3})+)(?!\\d)");
  private static final List<String> DIETS = List.of("thuan chay", "an chay", "mon chay", "vegan", "vegetarian");
  private static final List<String> CATEGORIES = List.of("bo", "hai san", "ca", "tom", "ga", "heo", "salad",
      "steak", "mon chinh", "khai vi", "trang mieng", "do uong", "thuc uong", "nuoc uong",
      "ruou", "pasta", "mi", "sot", "nuong", "cay");
  private static final Pattern ALLERGY = Pattern.compile("\\b(?:di ung|kieng|an kieng|tranh|khong an|khong dung|khong co|khong chua|khong the an)\\b");

  static String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT).replace('đ', 'd').strip();
  }

  static String safe(String value) {
    return PERSONAL.matcher(EMAIL.matcher(PHONE.matcher(CODE.matcher(value).replaceAll("[mã đặt bàn]"))
        .replaceAll("[số riêng tư]")).replaceAll("[email]")).replaceAll("[thông tin cá nhân]");
  }

  static String bookingCode(String text) {
    Matcher matcher = CODE.matcher(text);
    return matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : null;
  }

  static ChatIntent parse(String message, ChatIntent previous, LocalDate today) {
    String text = normalize(message);
    LocalDate date = date(text, today);
    LocalTime time = time(text);
    Integer guests = matchNumber(PARTY, text);
    BigDecimal budget = budget(text);
    String preference = preference(text);
    Kind kind = kind(text, message);
    if (kind == Kind.INFO && previous != null && (date != null || time != null || guests != null
        || (previous.kind == Kind.MENU && (budget != null || !preference.isBlank())))) {
      if (previous.kind == Kind.AVAILABILITY || previous.kind == Kind.DRAFT || previous.kind == Kind.MENU) {
        kind = previous.kind;
      }
    }
    ChatIntent intent = new ChatIntent(kind, date, time, guests, budget, preference);
    return intent.inherit(previous);
  }

  private static Kind kind(String text, String message) {
    if (bookingCode(message) != null || contains(text, "tra cuu", "lich dat cua", "don dat cua", "dat ban cua",
        "lich dat ban cua", "dat cho cua", "dat ban cua toi", "kiem tra ma", "tinh trang dat ban", "trang thai dat ban")) {
      return Kind.LOOKUP;
    }
    if (contains(text, "mo cua", "dong cua", "gio hoat dong", "chinh sach", "quy dinh", "dia chi", "hotline",
        "lien he", "doi lich", "huy dat", "check-in", "check in", "no-show", "no show",
        "dat ban truoc bao lau", "dat ban truoc", "dat ban nhu the nao", "doi so khach")) {
      return Kind.INFO;
    }
    if (contains(text, "ban trong", "con ban", "het ban", "co ban", "kiem tra ban", "kiem tra cho", "con cho",
        "co cho trong", "con trong", "co cho khong")) return Kind.AVAILABILITY;
    if (contains(text, "dat ban", "dat cho", "giu ban", "giu cho", "dien form")) return Kind.DRAFT;
    if (contains(text, "mon", "thuc don", "menu", "an gi", "ngan sach", "gia bao nhieu", "gia cua", "tu van",
        "goi y mon", "goi y an", "buffet", "combo") || containsToken(text, CATEGORIES) || containsToken(text, DIETS)) return Kind.MENU;
    if (text.matches("(?:xin )?chao[ !.]*|hello[ !.]*|hi[ !.]*|cam on[ !.]*|ban la ai[? !.]*")) {
      return Kind.GREETING;
    }
    return Kind.INFO;
  }

  ChatIntent modelFields(JsonNode output, ChatIntent previous) {
    if (output == null || !output.isObject()) return this;
    Kind next = kind;
    if (kind == Kind.INFO && !output.path("intent").asText().isBlank()) {
      try { next = Kind.valueOf(output.path("intent").asText()); } catch (IllegalArgumentException ignored) {}
    }
    Integer count = guests;
    if (count == null && output.path("guests").isIntegralNumber() && output.path("guests").canConvertToInt()) {
      int proposed = output.get("guests").intValue();
      if (proposed >= 1 && proposed <= 99) count = proposed;
    }
    BigDecimal amount = budget;
    if (amount == null && output.path("budget").isIntegralNumber() && output.path("budget").canConvertToLong()) {
      long proposed = output.get("budget").longValue();
      if (proposed > 0 && proposed <= 1_000_000_000) amount = BigDecimal.valueOf(proposed);
    }
    String modelPreference = output.path("preference").asText();
    if (modelPreference.length() > 120) modelPreference = "";
    return new ChatIntent(next, date == null ? isoDate(output.path("date").asText()) : date,
        time == null ? isoTime(output.path("time").asText()) : time, count, amount,
        preference.isBlank() ? preference(normalize(modelPreference)) : preference).inherit(previous);
  }

  private ChatIntent inherit(ChatIntent previous) {
    if (previous == null) return this;
    if (kind == Kind.MENU && previous.kind == Kind.MENU) {
      return new ChatIntent(kind, null, null, guests != null ? guests : previous.guests,
          budget != null ? budget : previous.budget, preference.isBlank() ? previous.preference : preference);
    }
    if ((kind == Kind.DRAFT || kind == Kind.AVAILABILITY)
        && (previous.kind == Kind.DRAFT || previous.kind == Kind.AVAILABILITY)) {
      return new ChatIntent(kind, date != null ? date : previous.date, time != null ? time : previous.time,
          guests != null ? guests : previous.guests, null, "");
    }
    return this;
  }

  Document context() {
    return new Document("kind", kind.name()).append("date", date == null ? "" : date.toString())
        .append("time", time == null ? "" : time.toString()).append("guests", guests)
        .append("budget", budget == null ? null : budget.longValue()).append("preference", preference);
  }

  static ChatIntent stored(Document doc) {
    if (doc == null) return null;
    return new ChatIntent(Kind.valueOf(doc.getString("kind")), isoDate(doc.getString("date")),
        isoTime(doc.getString("time")), doc.getInteger("guests"),
        doc.get("budget") instanceof Number number ? BigDecimal.valueOf(number.longValue()) : null, doc.getString("preference"));
  }

  static Map<String, Object> schema() {
    return Map.of("type", "OBJECT", "required", List.of("intent"), "properties", Map.of(
        "intent", Map.of("type", "STRING", "enum", List.of("INFO", "MENU", "AVAILABILITY", "DRAFT", "LOOKUP", "GREETING")),
        "date", Map.of("type", "STRING"), "time", Map.of("type", "STRING"),
        "guests", Map.of("type", "INTEGER"), "budget", Map.of("type", "INTEGER"), "preference", Map.of("type", "STRING")));
  }

  static boolean dietaryRestriction(String text) { return ALLERGY.matcher(normalize(text)).find(); }

  private static String preference(String text) {
    if (ALLERGY.matcher(text).find()) return "restriction";
    if (containsToken(text, DIETS)) return "chay";
    return CATEGORIES.stream().filter(category -> token(text, category)).findFirst().orElse("");
  }

  static boolean token(String text, String word) {
    return (" " + text.replaceAll("[^a-z0-9 ]+", " ") + " ").contains(" " + word + " ");
  }

  private static boolean containsToken(String text, List<String> terms) {
    return terms.stream().anyMatch(term -> token(text, term));
  }

  private static boolean contains(String text, String... terms) {
    return java.util.Arrays.stream(terms).anyMatch(text::contains);
  }

  private static Integer matchNumber(Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
  }

  private static LocalDate date(String text, LocalDate today) {
    Matcher iso = ISO.matcher(text);
    if (iso.find()) return isoDate(iso.group());
    Matcher date = DATE.matcher(text);
    if (date.find()) {
      try {
        var parsed = LocalDate.of(date.group(3) == null ? today.getYear() : Integer.parseInt(date.group(3)),
            Integer.parseInt(date.group(2)), Integer.parseInt(date.group(1)));
        return date.group(3) == null && parsed.isBefore(today) ? parsed.plusYears(1) : parsed;
      } catch (java.time.DateTimeException ignored) { return null; }
    }
    if (text.contains("ngay kia")) return today.plusDays(2);
    if (text.contains("ngay mai") || text.contains("toi mai")) return today.plusDays(1);
    if (text.contains("hom nay") || text.contains("toi nay") || text.contains("chieu nay")) return today;
    return null;
  }

  private static LocalTime time(String text) {
    Matcher matcher = CLOCK.matcher(text);
    if (!matcher.find() || text.substring(Math.max(0, matcher.start() - 6), matcher.start()).endsWith("truoc ")) return null;
    int hour = Integer.parseInt(matcher.group(1));
    String digits = java.util.stream.IntStream.rangeClosed(2, 4).mapToObj(matcher::group)
        .filter(java.util.Objects::nonNull).findFirst().orElse("0");
    String before = text.substring(0, matcher.start()).stripTrailing();
    String after = text.substring(matcher.end()).stripLeading();
    boolean evening = before.matches(".*\\b(?:chieu|toi)(?: nay| mai| ngay mai)?(?: luc)?$")
        || after.matches("^(?:toi|chieu|pm)(?:\\b.*)?$");
    if (hour < 12 && evening) hour += 12;
    try { return LocalTime.of(hour, Integer.parseInt(digits)); } catch (java.time.DateTimeException ignored) { return null; }
  }

  private static BigDecimal budget(String text) {
    Matcher matcher = BUDGET.matcher(text);
    if (!matcher.find()) return null;
    String unit = matcher.group(2);
    String value = unit == null ? matcher.group(3) : matcher.group(1);
    long multiplier = unit == null ? 1 : switch (unit) {
      case "trieu" -> 1_000_000;
      case "k", "nghin", "ngan" -> 1000;
      default -> 1;
    };
    String numeric = multiplier == 1 ? value.replaceAll("[,.]", "") : value.replace(',', '.');
    var amount = new BigDecimal(numeric).multiply(BigDecimal.valueOf(multiplier)).setScale(0, java.math.RoundingMode.DOWN);
    return amount.signum() > 0 && amount.compareTo(BigDecimal.valueOf(1_000_000_000)) <= 0 ? amount : null;
  }

  private static LocalDate isoDate(String value) {
    try { return LocalDate.parse(value); } catch (DateTimeParseException | NullPointerException ignored) { return null; }
  }

  private static LocalTime isoTime(String value) {
    try { return LocalTime.parse(value); } catch (DateTimeParseException | NullPointerException ignored) { return null; }
  }
}
