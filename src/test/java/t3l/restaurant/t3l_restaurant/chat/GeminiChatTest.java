package t3l.restaurant.t3l_restaurant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

class GeminiChatTest {
  private final ChatStore store = mock(ChatStore.class);
  private final HttpClient http = mock(HttpClient.class);
  private final GeminiChat gemini = new GeminiChat(new ChatSettings("test-key", "test", "test-embedding",
      128, 2, 10, 8, ""), store, new ObjectMapper(), http);

  @Test
  void handlesProviderQuotaAndNetworkFailureAndAllowsLaterRequests() {
    when(store.allow(eq("gemini"), any(), eq(10))).thenReturn(true);
    var quota = response(429, "{}");
    var empty = response(200, "");
    var valid = response(200, """
        {"candidates":[{"finishReason":"STOP","content":{"parts":[
          {"thought":true,"text":"thinking"},{"text":"{\\"intent\\":\\"INFO\\"}"}]}}]}
        """);
    when(http.sendAsync(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenReturn(CompletableFuture.completedFuture(quota))
        .thenReturn(CompletableFuture.failedFuture(new ConnectException("offline")))
        .thenReturn(CompletableFuture.completedFuture(empty))
        .thenReturn(CompletableFuture.completedFuture(valid));
    for (int attempt = 0; attempt < 3; attempt++) {
      assertThatThrownBy(() -> gemini.structured("test", Map.of(), ChatIntent.schema()))
          .isInstanceOf(GeminiChat.Unavailable.class);
    }
    assertThat(gemini.structured("test", Map.of(), ChatIntent.schema()).path("intent").asText()).isEqualTo("INFO");
    var request = ArgumentCaptor.forClass(HttpRequest.class);
    verify(http, times(4)).sendAsync(request.capture(), any());
    assertThat(request.getValue().uri().getRawQuery()).isNull();
    assertThat(request.getValue().headers().firstValue("x-goog-api-key")).contains("test-key");
  }

  @Test
  void enforcesLocalQuotaWithoutContactingTheProvider() {
    when(store.allow(eq("gemini"), any(), eq(10))).thenReturn(false);
    assertThatThrownBy(() -> gemini.embedding("test", true)).isInstanceOf(GeminiChat.Unavailable.class);
    verifyNoInteractions(http);
  }

  @SuppressWarnings("unchecked")
  private static HttpResponse<String> response(int status, String body) {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    return response;
  }
}
