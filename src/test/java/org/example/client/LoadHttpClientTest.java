package org.example.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.example.dto.TestPlanSpec.HttpMethod;
import org.example.dto.TestPlanSpec.Request;
import org.example.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoadHttpClientTest {

  private LoadHttpClient client;
  private Map<String, String> globalHeaders;
  private Map<String, String> variables;

  @Mock private HttpClient httpClientMock;

  @Mock private HttpResponse<String> httpResponseMock;

  @BeforeEach
  void setUp() throws Exception {
    globalHeaders =
        Map.of(
            "Authorization", "Bearer token123",
            "User-Agent", "LoadHttpClient/1.0");
    variables =
        Map.of(
            "userId", "12345",
            "version", "v1");

    client = new LoadHttpClient("https://api.example.com", 5, 10, globalHeaders, variables);

    // inject mock HttpClient via reflection
    var field = LoadHttpClient.class.getDeclaredField("httpClient");
    field.setAccessible(true);
    field.set(client, httpClientMock);
  }

  // ---------------- Constructor tests ----------------

  @Test
  void constructor_withValidParameters_shouldCreateClient() {
    assertNotNull(client);
    assertEquals("https://api.example.com", client.getBaseUrl());
    assertEquals(Duration.ofSeconds(10), client.getRequestTimeout());
    assertEquals(globalHeaders, client.getHeaders());
    assertEquals(variables, client.getVariables());
  }

  @Test
  void constructor_withDefaultTimeout_shouldUseDefault() {
    var defaultClient = new LoadHttpClient("https://api.example.com", 5, globalHeaders, variables);
    assertEquals(Duration.ofSeconds(30), defaultClient.getRequestTimeout());
  }

  @Test
  void constructor_withNullBaseUrl_shouldThrowException() {
    assertThrows(
        NullPointerException.class, () -> new LoadHttpClient(null, 5, globalHeaders, variables));
  }

  @Test
  void constructor_withEmptyBaseUrl_shouldThrowException() {
    assertThrows(
        IllegalArgumentException.class, () -> new LoadHttpClient("", 5, globalHeaders, variables));
  }

  @Test
  void constructor_withWhitespaceBaseUrl_shouldThrowException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new LoadHttpClient("   ", 5, globalHeaders, variables));
  }

  @Test
  void constructor_withTrailingSlashBaseUrl_shouldNormalize() {
    var clientWithSlash =
        new LoadHttpClient("https://api.example.com/", 5, globalHeaders, variables);
    assertEquals("https://api.example.com", clientWithSlash.getBaseUrl());
  }

  // ---------------- Validation ----------------

  @Test
  void execute_withNullRequest_shouldThrowException() {
    assertThrows(NullPointerException.class, () -> client.execute(null));
  }

  // ---------------- Variable resolution ----------------

  @SuppressWarnings("unchecked")
  @Test
  void buildHttpRequest_withVariablesInPath_shouldResolveCorrectly() throws Exception {
    when(httpResponseMock.statusCode()).thenReturn(200);
    when(httpResponseMock.body()).thenReturn("ok");
    when(httpResponseMock.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));

    when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn((HttpResponse) httpResponseMock);

    var request = new Request();
    request.setMethod(HttpMethod.GET);
    request.setPath("/{{version}}/users/{{userId}}");

    var resp = client.execute(request);
    assertEquals(200, resp.getStatusCode());
    verify(httpClientMock)
        .send(argThat(r -> r.uri().toString().contains("/v1/users/12345")), any());
  }

  @SuppressWarnings("unchecked")
  @Test
  void buildQueryString_withVariables_shouldEncode() throws Exception {
    when(httpResponseMock.statusCode()).thenReturn(200);
    when(httpResponseMock.body()).thenReturn("ok");
    when(httpResponseMock.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));

    when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn((HttpResponse) httpResponseMock);

    var request = new Request();
    request.setMethod(HttpMethod.GET);
    request.setPath("/users");
    request.setQuery(
        Map.of(
            "id", "{{userId}}",
            "search", "hello world"));

    client.execute(request);

    verify(httpClientMock)
        .send(
            argThat(
                r ->
                    r.uri().toString().contains("id=12345")
                        && r.uri().toString().contains("search=hello+world")),
            any());
  }

  // ---------------- Headers ----------------

  @SuppressWarnings("unchecked")
  @Test
  void buildHttpRequest_withOverridingHeaders_shouldUseRequestHeaders() throws Exception {
    when(httpResponseMock.statusCode()).thenReturn(200);
    when(httpResponseMock.body()).thenReturn("ok");
    when(httpResponseMock.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));

    when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn((HttpResponse) httpResponseMock);

    var request = new Request();
    request.setMethod(HttpMethod.GET);
    request.setPath("/users");
    request.setHeaders(Map.of("Authorization", "Bearer override"));

    client.execute(request);

    var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClientMock).send(captor.capture(), any());
    HttpRequest built = captor.getValue();

    assertTrue(built.headers().firstValue("Authorization").isPresent());
    assertEquals("Bearer override", built.headers().firstValue("Authorization").get());
  }

  // ---------------- JSON serialization ----------------

  @Test
  void buildHttpRequest_withJsonSerializationFailure_shouldThrowException() {
    try (MockedStatic<JsonUtil> jsonUtilMock = mockStatic(JsonUtil.class)) {
      jsonUtilMock
          .when(() -> JsonUtil.toJson(any()))
          .thenThrow(new JsonProcessingException("Serialization failed") {});

      var request = new Request();
      request.setMethod(HttpMethod.POST);
      request.setPath("/users");
      request.setBody(Map.of("bad", "§"));

      var ex = assertThrows(RuntimeException.class, () -> client.execute(request));
      assertTrue(ex.getMessage().contains("Failed to serialize request body"));
    }
  }

  // ---------------- Async ----------------

  @SuppressWarnings("unchecked")
  @Test
  void executeAsync_success() {
    when(httpResponseMock.statusCode()).thenReturn(200);
    when(httpResponseMock.body()).thenReturn("ok");
    when(httpResponseMock.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));

    when(httpClientMock.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(CompletableFuture.completedFuture((HttpResponse) httpResponseMock));

    var req = new Request();
    req.setMethod(HttpMethod.GET);
    req.setPath("/users");

    var result = client.executeAsync(req).join();
    assertEquals(200, result.getStatusCode());
  }

  @SuppressWarnings("unchecked")
  @Test
  void executeAsync_failure() {
    when(httpClientMock.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));

    var req = new Request();
    req.setMethod(HttpMethod.GET);
    req.setPath("/users");

    var future = client.executeAsync(req);
    assertThrows(RuntimeException.class, future::join);
  }

  // ---------------- Timeout ----------------

  @SuppressWarnings("unchecked")
  @Test
  void execute_withTimeout_shouldThrow() throws Exception {
    when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new HttpTimeoutException("timeout"));

    var req = new Request();
    req.setMethod(HttpMethod.GET);
    req.setPath("/slow");

    RuntimeException ex = assertThrows(RuntimeException.class, () -> client.execute(req));
    assertTrue(ex.getMessage().contains("timed out"));
  }

  // ---------------- Cleanup ----------------

  @Test
  void close_shouldNotThrow() {
    assertDoesNotThrow(() -> client.close());
  }
}
