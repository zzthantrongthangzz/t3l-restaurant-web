package t3l.restaurant.t3l_restaurant.service;

public class BookingException extends IllegalArgumentException {
  private final String field;

  public BookingException(String field, String message) {
    super(message);
    this.field = field;
  }

  public String getField() {
    return field;
  }
}
