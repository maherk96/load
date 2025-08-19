package org.example.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.Closeable;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.Getter;
import org.example.client.response.RestResponseData;
import org.example.dto.TestPlanSpec; // <-- your DTO
import org.example.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP client implementation for executing REST requests using Request DTO. Supports variable
 * resolution, header management, and timeout configuration. Retry logic removed.
 */
@Getter
public class LoadHttpClient implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(LoadHttpClient.class);

  private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 30;

  private final HttpClient httpClient;

  private final Map<String, String> headers;
  private final Map<String, String> variables;
  private final String baseUrl;
  private final Duration requestTimeout;

  public LoadHttpClient(
      String baseUrl,
      int connTimeOutSeconds,
      Map<String, String> headers,
      Map<String, String> variables) {
    this(baseUrl, connTimeOutSeconds, DEFAULT_REQUEST_TIMEOUT_SECONDS, headers, variables);
  }

  public LoadHttpClient(
      String baseUrl,
      int connTimeOutSeconds,
      int requestTimeoutSeconds,
      Map<String, String> headers,
      Map<String, String> variables) {
    this.baseUrl = validateAndNormalizeBaseUrl(baseUrl);
    this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connTimeOutSeconds)).build();
    this.headers = headers != null ? Map.copyOf(headers) : Map.of();
    this.variables = variables != null ? Map.copyOf(variables) : Map.of();

    log.info(
        "LoadHttpClient initialized - Base URL: {}, Connection timeout: {}s, Request timeout: {}s",
        baseUrl,
        connTimeOutSeconds,
        requestTimeoutSeconds);
  }

  /** Executes a request (no retry logic). */
  public RestResponseData execute(TestPlanSpec.Request request) {
    Objects.requireNonNull(request, "Request cannot be null");

    try {
      var startTime = System.nanoTime();
      var httpRequest = buildHttpRequest(request);

      log.debug("Executing {} request to {}", request.getMethod(), httpRequest.uri());

      var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      var duration = (System.nanoTime() - startTime) / 1_000_000;

      var result = buildResponseData(response, duration);

      log.debug("Request completed in {} ms with status {}", duration, response.statusCode());
      return result;

    } catch (HttpTimeoutException e) {
      throw new RuntimeException(
          "Request timed out after " + requestTimeout.getSeconds() + "s: " + e.getMessage(), e);
    } catch (Exception e) {
      throw new RuntimeException("Error executing request: " + e.getMessage(), e);
    }
  }

  /** Async execution (no retry logic). */
  public CompletableFuture<RestResponseData> executeAsync(TestPlanSpec.Request request) {
    Objects.requireNonNull(request, "Request cannot be null");

    var startTime = System.nanoTime();

    try {
      var httpRequest = buildHttpRequest(request);

      log.debug("Executing async {} request to {}", request.getMethod(), httpRequest.uri());

      return httpClient
          .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
          .thenApply(
              response -> {
                var duration = (System.nanoTime() - startTime) / 1_000_000;
                var result = buildResponseData(response, duration);
                log.info(
                    "Async request completed in {} ms with status {}",
                    duration,
                    response.statusCode());
                return result;
              })
          .exceptionally(
              throwable -> {
                throw new RuntimeException(
                    "Async request failed: " + throwable.getMessage(), throwable);
              });
    } catch (Exception e) {
      return CompletableFuture.failedFuture(
          new RuntimeException("Error building async request: " + e.getMessage(), e));
    }
  }

  private HttpRequest buildHttpRequest(TestPlanSpec.Request request) {
    try {
      var resolvedPath = resolveVars(request.getPath(), variables);
      var url = baseUrl + (resolvedPath != null ? resolvedPath : "");

      if (request.getQuery() != null && !request.getQuery().isEmpty()) {
        var queryStr = buildQueryString(request.getQuery(), variables);
        url += "?" + queryStr;
      }

      var requestBuilder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(requestTimeout);

      // global headers
      headers.forEach(requestBuilder::header);

      // request-specific headers override
      if (request.getHeaders() != null) {
        request.getHeaders().forEach(requestBuilder::setHeader);
      }

      if (request.getBody() != null) {
        try {
          var jsonBody = JsonUtil.toJson(request.getBody());
          requestBuilder
              .method(request.getMethod().name(), HttpRequest.BodyPublishers.ofString(jsonBody))
              .header("Content-Type", "application/json");
        } catch (JsonProcessingException e) {
          throw new RuntimeException("Failed to serialize request body: " + e.getMessage(), e);
        }
      } else {
        requestBuilder.method(request.getMethod().name(), HttpRequest.BodyPublishers.noBody());
      }

      return requestBuilder.build();

    } catch (Exception e) {
      throw new RuntimeException("Error building HTTP request: " + e.getMessage(), e);
    }
  }

  private RestResponseData buildResponseData(HttpResponse<String> response, long durationMs) {
    var result = new RestResponseData();
    result.setStatusCode(response.statusCode());
    result.setHeaders(
        response.headers().map().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> String.join(",", e.getValue()))));
    result.setBody(response.body());
    result.setResponseTimeMs(durationMs);
    return result;
  }

  private String buildQueryString(Map<String, String> query, Map<String, String> variables) {
    return query.entrySet().stream()
        .filter(e -> e.getKey() != null && e.getValue() != null)
        .map(
            e ->
                encode(resolveVars(e.getKey(), variables))
                    + "="
                    + encode(resolveVars(e.getValue(), variables)))
        .collect(Collectors.joining("&"));
  }

  private String encode(String value) {
    return value != null ? URLEncoder.encode(value, StandardCharsets.UTF_8) : "";
  }

  private String resolveVars(String text, Map<String, String> variables) {
    if (text == null || text.isEmpty() || variables.isEmpty()) {
      return text;
    }
    var result = text;
    for (var entry : variables.entrySet()) {
      var placeholder = "{{" + entry.getKey() + "}}";
      if (result.contains(placeholder)) {
        result = result.replace(placeholder, entry.getValue());
      }
    }
    return result;
  }

  private String validateAndNormalizeBaseUrl(String baseUrl) {
    Objects.requireNonNull(baseUrl, "Base URL cannot be null");
    var trimmed = baseUrl.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("Base URL cannot be empty");
    }
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }

  @Override
  public void close() {
    log.info("LoadHttpClient closed");
  }
}
