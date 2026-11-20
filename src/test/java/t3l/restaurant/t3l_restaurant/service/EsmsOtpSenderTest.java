package t3l.restaurant.t3l_restaurant.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

class EsmsOtpSenderTest {
  @Test
  void sendsTheOtpUsingTheDocumentedJsonContract() {
    var requestBody = new AtomicReference<JsonNode>();
    var reply = new AtomicReference<>("""
        {"CodeResult":"100","SMSID":"mock-id"}
        """);
    var json = new ObjectMapper();
    var builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
      assertEquals(HttpMethod.POST, request.getMethod());
      assertEquals("rest.esms.vn", request.getURI().getHost());
      assertEquals("https", request.getURI().getScheme());
      assertEquals("/MainService.svc/json/SendMultipleMessage_V4_post_json/", request.getURI().getPath());
      assertNull(request.getURI().getQuery());
      assertEquals(MediaType.APPLICATION_JSON, request.getHeaders().getContentType());
      requestBody.set(json.readTree(body));
      var response = new MockClientHttpResponse(reply.get().getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
      response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
      return response;
    });
    var sender = new EsmsOtpSender("test-key", "test-secret", "T3L", "Mã xác minh {otp}", false,
        "0900000000", "0.0.0.0", builder);

    assertTrue(sender.send("0900000000", "012345", "mock-request"));
    JsonNode body = requestBody.get();
    assertEquals("test-key", body.path("ApiKey").asText());
    assertEquals("test-secret", body.path("SecretKey").asText());
    assertEquals("T3L", body.path("Brandname").asText());
    assertEquals("0900000000", body.path("Phone").asText());
    assertEquals("Mã xác minh 012345", body.path("Content").asText());
    assertEquals("2", body.path("SmsType").asText());
    assertEquals("1", body.path("IsUnicode").asText());
    assertEquals("0", body.path("Sandbox").asText());
    assertEquals("mock-request", body.path("RequestId").asText());

    reply.set("""
        {"CodeResult":"146","SMSID":"id-with-template-error"}
        """);
    assertFalse(sender.send("0900000000", "012345", "mock-error"));
    reply.set("""
        {"CodeResult":"100","SMSID":null}
        """);
    assertFalse(sender.send("0900000000", "012345", "mock-incomplete"));
  }

  @Test
  void localModeAcceptsOnlyLoopbackAndNeverCallsEsms() {
    var builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
      fail("Local OTP must never call eSMS");
      return null;
    });
    var sender = new EsmsOtpSender("test-key", "test-secret", "T3L", "{otp}", true,
        "+84 900 000 000", "127.0.0.1", builder);
    var request = new MockHttpServletRequest();
    assertTrue(sender.local(request));
    assertTrue(sender.localPhone("0900000000"));
    assertFalse(sender.live());
    assertFalse(sender.send("0900000000", "012345", "mock-request"));
    request.setRemoteAddr("192.0.2.1");
    assertFalse(sender.local(request));
    request.setRemoteAddr("127.0.0.1");
    request.setServerName("example.invalid");
    assertFalse(sender.local(request));
  }
}
