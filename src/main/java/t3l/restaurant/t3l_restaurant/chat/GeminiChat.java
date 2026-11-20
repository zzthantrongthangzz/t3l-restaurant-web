package t3l.restaurant.t3l_restaurant.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
class GeminiChat {
  private final ChatSettings settings;
  private final ChatStore store;
  private final ObjectMapper json;
  private final HttpClient http;
  private final Semaphore concurrency = new Semaphore(4);

  GeminiChat(ChatSettings settings, ChatStore store, ObjectMapper json) {
    this.settings = settings;
    this.store = store;
    this.json = json;
    this.http = settings.configured() ? HttpClient.newBuilder().connectTimeout(settings.timeout).build() : null;
  }

  boolean configured() { return settings.configured(); }

  JsonNode structured(String system, Object input, Map<String, ?> schema) {
    try {
      var response = invoke(settings.model, "generateContent", Map.of(
          "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
          "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", json.writeValueAsString(input))))),
          "generationConfig", Map.of("temperature", 0, "maxOutputTokens", 600,
              "responseMimeType", "application/json", "responseSchema", schema)));
      var candidate = response.path("candidates").path(0);
      if (!"STOP".equals(candidate.path("finishReason").asText())) throw new Unavailable();
      for (JsonNode part : candidate.path("content").path("parts")) {
        if (part.hasNonNull("text") && !part.path("thought").asBoolean()) return json.readTree(part.get("text").asText());
      }
      throw new Unavailable();
    } catch (IOException error) {
      throw new Unavailable();
    }
  }

  List<Double> embedding(String input, boolean query) {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("model", "models/" + settings.embeddingModel);
    String text = input;
    if ("gemini-embedding-001".equals(settings.embeddingModel)) {
      body.put("taskType", query ? "RETRIEVAL_QUERY" : "RETRIEVAL_DOCUMENT");
    } else if (settings.embeddingModel.startsWith("gemini-embedding-2")) {
      text = (query ? "task: question answering | query: " : "title: T3L Restaurant | text: ") + input;
    }
    body.put("content", Map.of("parts", List.of(Map.of("text", text))));
    body.put("outputDimensionality", settings.dimensions);
    JsonNode values = invoke(settings.embeddingModel, "embedContent", body).path("embedding").path("values");
    if (!values.isArray() || values.size() != settings.dimensions) throw new Unavailable();
    List<Double> result = new ArrayList<>(settings.dimensions);
    for (JsonNode item : values) {
      if (!item.isNumber() || !Double.isFinite(item.doubleValue())) throw new Unavailable();
      result.add(item.doubleValue());
    }
    return result;
  }

  private JsonNode invoke(String model, String operation, Map<String, ?> body) {
    if (!configured() || !model.matches("[A-Za-z0-9._-]{1,100}") || !concurrency.tryAcquire()) throw new Unavailable();
    java.util.concurrent.CompletableFuture<HttpResponse<String>> pending = null;
    try {
      if (!store.allow("gemini", Duration.ofDays(1), settings.callsPerDay)) throw new Unavailable();
      var request = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
              + model + ":" + operation))
          .header("x-goog-api-key", settings.apiKey).header("Content-Type", "application/json")
          .timeout(settings.timeout).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
      pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
      var response = pending.get(settings.timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (response.statusCode() != 200) throw new Unavailable();
      return json.readTree(response.body());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new Unavailable();
    } catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException
             | MongoException | IllegalArgumentException error) {
      throw new Unavailable();
    } finally {
      if (pending != null && !pending.isDone()) pending.cancel(true);
      concurrency.release();
    }
  }

  static class Unavailable extends RuntimeException {}

  @PreDestroy
  void close() {
    if (http != null) http.shutdownNow();
  }
}
