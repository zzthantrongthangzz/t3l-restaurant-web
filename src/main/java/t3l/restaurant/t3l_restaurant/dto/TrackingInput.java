package t3l.restaurant.t3l_restaurant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class TrackingInput {
  private TrackingInput() {}

  public record Consent(@NotNull Boolean allowed) {}
  public record Batch(@NotEmpty @Size(max = 24) List<@NotNull @Valid Event> events) {}
  public record Event(@NotNull UUID id, @NotBlank @Size(max = 25) String type,
                      @NotBlank @Size(max = 30) String page,
                      @Positive Long dishId, @Size(max = 90) String term,
                      @Min(0) @Max(100) Integer quantity, @Min(1) @Max(15) Integer seconds) {}
}
