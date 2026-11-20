package t3l.restaurant.t3l_restaurant.chat;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ChatSettings {
  final String apiKey;
  final String model;
  final String embeddingModel;
  final int dimensions;
  final Duration timeout;
  final int callsPerDay;
  final int requestsPerMinute;
  final String vectorUri;

  ChatSettings(@Value("${app.chat.api-key}") String apiKey, @Value("${app.chat.model}") String model,
      @Value("${app.chat.embedding-model}") String embeddingModel,
      @Value("${app.chat.embedding-dimensions}") int dimensions,
      @Value("${app.chat.timeout-seconds}") int timeout,
      @Value("${app.chat.calls-per-day}") int callsPerDay,
      @Value("${app.chat.requests-per-minute}") int requestsPerMinute,
      @Value("${app.chat.vector-uri}") String vectorUri) {
    this.apiKey = apiKey.strip();
    this.model = model.strip();
    this.embeddingModel = embeddingModel.strip();
    this.dimensions = Math.clamp(dimensions, 128, 3072);
    this.timeout = Duration.ofSeconds(Math.clamp(timeout, 2, 15));
    this.callsPerDay = Math.clamp(callsPerDay, 1, 20000);
    this.requestsPerMinute = Math.clamp(requestsPerMinute, 1, 60);
    this.vectorUri = vectorUri.strip();
  }

  boolean configured() { return !apiKey.isEmpty(); }
}
