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
      "(?iu)\\b(?:t[ôo]i t[êe]n|m[ìi]nh t[êe]n|t[êe]n (?:c[ủu]a t[ôo]i|kh[áa]ch|l[àa])|h[ọo] v[àa] t[êe]n|li[êe]n h[ệe] v[ớo]i t[ôo]i)(?:\\s*[:=]\\s*|\\s+)[^\\n,.!?;]{2,80}");
  private static final Pattern PARTY = Pattern.compile("(?<![\\w.,])([+-]?\\d+(?:[.,]\\d+)?)\\s*(?:nguoi|khach|vi|suat)\\b");
  private static final Pattern CLOCK = Pattern.compile(
      "(?<![\\d/])([+-]?\\d+)\\s*(?::(\\d*)|(?:h|gio)(?:\\s*(\\d+)(?![\\d/:-]|\\s*(?:nguoi|khach|vi|suat)\\b))?)(?![a-z0-9])");
  private static final Pattern ISO = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
  private static final Pattern DATE = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}))?\\b");
  private static final Pattern BUDGET = Pattern.compile(
      "(?<![\\d.,])(-?\\d+(?:[,.]\\d+)*)\\s*(?:(k|nghin|ngan|trieu|vnd|dong|d)\\b|₫)|(?<![\\d.,])(-?\\d{1,3}(?:[.,]\\d{3})+)(?![\\d.,])");
  private static final BigDecimal MAX_BUDGET = BigDecimal.valueOf(1_000_000_000);
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
    Integer guests = guests(text);
    BigDecimal budget = budget(text);
    String preference = preference(text);
    Kind kind = kind(text);
    if (kind == null && previous != null && (date != null || time != null || guests != null
        || invalidBookingField(message, today) != null
        || (previous.kind == Kind.MENU && (budget != null || !preference.isBlank())))) {
      if (previous.kind == Kind.AVAILABILITY || previous.kind == Kind.DRAFT || previous.kind == Kind.MENU) {
        kind = previous.kind;
      }
    }
    return new ChatIntent(kind == null ? Kind.INFO : kind, date, time, guests, budget, preference).inherit(previous);
  }

  static boolean needsClassification(String message) { return kind(normalize(message)) == null; }

  private static Kind kind(String text) {
    if (contains(text, "lich dat cua", "don dat cua", "dat ban cua", "dat cho cua", "kiem tra ma", "tinh trang dat ban",
        "trang thai dat ban", "lich su dat ban", "lich da dat", "lich dat ban sap toi", "lich dat ban truoc day",
        "xem lich hen", "lich hen cua", "toi da dat ban", "minh da dat ban")) {
      return Kind.LOOKUP;
    }
    if (contains(text, "mo cua", "dong cua", "gio hoat dong", "chinh sach", "quy dinh", "dia chi", "hotline",
        "lien he", "doi lich", "huy dat", "check-in", "check in", "no-show", "no show",
        "dat ban truoc bao lau", "dat ban truoc", "dat ban nhu the nao", "doi so khach")) {
      return Kind.INFO;
    }
    if (contains(text, "tra cuu") && contains(text, "dat ban", "dat cho")) return Kind.LOOKUP;
    if (contains(text, "ban trong", "con ban", "het ban", "co ban", "kiem tra ban", "kiem tra cho", "con cho",
        "co cho trong", "con trong", "co cho khong")) return Kind.AVAILABILITY;
    if (contains(text, "dat ban", "dat cho", "giu ban", "giu cho", "dien form")) return Kind.DRAFT;
    if (contains(text, "thuc don", "menu", "an gi", "ngan sach", "gia bao nhieu", "gia cua", "tu van",
        "di ung",
        "goi y mon", "goi y an", "buffet", "combo") || containsToken(text, CATEGORIES) || containsToken(text, DIETS)) return Kind.MENU;
    if (token(text, "mon")) return Kind.MENU;
    if (text.matches("(?:xin )?chao(?: ban| buoi (?:sang|trua|chieu|toi))?[ !.]*|hello[ !.]*|hi[ !.]*|cam on[ !.]*|ban la ai[? !.]*")) {
      return Kind.GREETING;
    }
    return null;
  }

  ChatIntent modelFields(JsonNode output, ChatIntent previous) {
    if (output == null || !output.isObject()) return this;
    Kind next = kind;
    if (kind == Kind.INFO && !output.path("intent").asText().isBlank()) {
      next = switch (output.path("intent").asText()) {
        case "MENU" -> Kind.MENU;
        case "AVAILABILITY" -> Kind.AVAILABILITY;
        case "DRAFT" -> Kind.DRAFT;
        default -> Kind.INFO;
      };
    }
    Integer count = guests;
    if (count == null && output.path("guests").isIntegralNumber() && output.path("guests").canConvertToInt()) {
      int proposed = output.get("guests").intValue();
      if (proposed >= 1 && proposed <= 99) count = proposed;
    }
    BigDecimal amount = budget;
    if (amount == null && output.path("budget").isIntegralNumber() && output.path("budget").canConvertToLong()) {
      long proposed = output.get("budget").longValue();
      amount = validatedBudget(BigDecimal.valueOf(proposed));
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
        "intent", Map.of("type", "STRING", "enum", List.of("INFO", "MENU", "AVAILABILITY", "DRAFT")),
        "date", Map.of("type", "STRING"), "time", Map.of("type", "STRING"),
        "guests", Map.of("type", "INTEGER"), "budget", Map.of("type", "INTEGER"), "preference", Map.of("type", "STRING")));
  }

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

  private static Integer guests(String text) {
    Matcher matcher = PARTY.matcher(text);
    if (!matcher.find()) return null;
    String count = matcher.group(1);
    if (!count.matches("\\+?\\d+")) return 0;
    String digits = count.replaceFirst("^\\+?0*(?=\\d)", "");
    return digits.length() > 3 ? 1000 : Integer.valueOf(digits);
  }

  static String invalidBookingField(String message, LocalDate today) {
    String text = normalize(message);
    if ((ISO.matcher(text).find() || DATE.matcher(text).find()) && date(text, today) == null) return "ngày đến";
    if (CLOCK.matcher(text).find() && time(text) == null) return "giờ đến";
    return null;
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
    String hours = matcher.group(1);
    if (hours.startsWith("-") || hours.length() > (hours.startsWith("+") ? 3 : 2)) return null;
    int hour = Integer.parseInt(hours);
    String digits = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
    String before = text.substring(0, matcher.start()).stripTrailing();
    String after = text.substring(matcher.end()).stripLeading();
    boolean evening = before.matches(".*\\b(?:chieu|toi)(?: nay| mai| ngay mai)?(?: luc)?$")
        || after.matches("^(?:toi|chieu|pm)(?:\\b.*)?$");
    if (digits != null && (digits.isEmpty() || digits.length() > 2)) return null;
    if (hour < 12 && evening) hour += 12;
    if (hour == 12 && (before.endsWith("sang") || after.matches("^(?:sang|am)(?:\\b.*)?$"))) hour = 0;
    try { return LocalTime.of(hour, digits == null ? 0 : Integer.parseInt(digits)); }
    catch (java.time.DateTimeException ignored) { return null; }
  }

  private static BigDecimal budget(String text) {
    Matcher matcher = BUDGET.matcher(text);
    if (!matcher.find()) return null;
    String unit = matcher.group(2);
    String value = matcher.group(1) == null ? matcher.group(3) : matcher.group(1);
    long multiplier = unit == null ? 1 : switch (unit) {
      case "trieu" -> 1_000_000;
      case "k", "nghin", "ngan" -> 1000;
      default -> 1;
    };
    boolean grouped = value.matches("-?\\d{1,3}(?:\\.\\d{3})+") || value.matches("-?\\d{1,3}(?:,\\d{3})+");
    String numeric = grouped && (multiplier == 1 || value.chars().filter(c -> c == '.' || c == ',').count() > 1)
        ? value.replaceAll("[,.]", "") : value.replace(',', '.');
    if (!numeric.matches("-?\\d+(?:\\.\\d+)?") || (multiplier == 1 && numeric.contains("."))) return BigDecimal.ZERO;
    var amount = new BigDecimal(numeric).multiply(BigDecimal.valueOf(multiplier)).setScale(0, java.math.RoundingMode.DOWN);
    return validatedBudget(amount);
  }

  private static BigDecimal validatedBudget(BigDecimal amount) {
    return amount.signum() > 0 && amount.compareTo(MAX_BUDGET) <= 0 ? amount : BigDecimal.ZERO;
  }

  private static LocalDate isoDate(String value) {
    try { return LocalDate.parse(value); } catch (DateTimeParseException | NullPointerException ignored) { return null; }
  }

  private static LocalTime isoTime(String value) {
    try { return LocalTime.parse(value); } catch (DateTimeParseException | NullPointerException ignored) { return null; }
  }
}
