package t3l.restaurant.t3l_restaurant.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class EsmsOtpSender {
  private static final String ENDPOINT = "https://rest.esms.vn/MainService.svc/json/SendMultipleMessage_V4_post_json/";
  private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "[::1]");
  private final RestClient client;
  private final String apiKey;
  private final String secretKey;
  private final String brandname;
  private final String template;
  private final boolean localRequested;
  private final boolean localEnabled;
  private final String localPhone;

  public EsmsOtpSender(@Value("${app.sms.api-key}") String apiKey, @Value("${app.sms.secret-key}") String secretKey,
      @Value("${app.sms.brandname}") String brandname, @Value("${app.sms.template}") String template,
      @Value("${app.sms.local-enabled}") boolean localEnabled, @Value("${app.sms.local-phone}") String localPhone,
      @Value("${server.address:}") String bindAddress, RestClient.Builder clientBuilder) {
    this.apiKey = apiKey;
    this.secretKey = secretKey;
    this.brandname = brandname;
    this.template = template;
    this.localRequested = localEnabled;
    this.localPhone = SmsOtpService.normalizePhone(localPhone);
    this.localEnabled = localEnabled && this.localPhone != null && LOOPBACK.contains(bindAddress);
    var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build());
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = clientBuilder.requestFactory(factory).build();
  }

  public boolean live() {
    return !localRequested && !apiKey.isBlank() && !secretKey.isBlank() && !brandname.isBlank() && template.contains("{otp}");
  }

  public boolean local(HttpServletRequest request) {
    return !live() && localEnabled && LOOPBACK.contains(request.getRemoteAddr())
        && ("localhost".equalsIgnoreCase(request.getServerName()) || LOOPBACK.contains(request.getServerName()));
  }

  public boolean localPhone(String phone) {
    return Objects.equals(phone, localPhone);
  }

  public boolean send(String phone, String code, String requestId) {
    if (!live()) return false;
    String content = template.replace("{otp}", code);
    String unicode = StandardCharsets.US_ASCII.newEncoder().canEncode(content) ? "0" : "1";
    try {
      JsonNode response = client.post().uri(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("ApiKey", apiKey, "SecretKey", secretKey, "Brandname", brandname,
              "Content", content, "Phone", phone, "SmsType", "2",
              "IsUnicode", unicode, "Sandbox", "0", "RequestId", requestId))
          .retrieve().body(JsonNode.class);
      return response != null && "100".equals(response.path("CodeResult").asText())
          && !response.path("SMSID").asText("").isBlank();
    } catch (RestClientException exception) {
      return false;
    }
  }
}
