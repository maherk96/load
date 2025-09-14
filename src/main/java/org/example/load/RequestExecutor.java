package org.example.load;

import org.example.client.LoadHttpClient;
import org.example.dto.TestPlanSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class RequestExecutor {

  private static final Logger log = LoggerFactory.getLogger(RequestExecutor.class);

  private final LoadHttpClient httpClient;
  private final AtomicInteger activeRequests = new AtomicInteger(0);

  public RequestExecutor(TestPlanSpec testPlanSpec) {
    var globalConfig = testPlanSpec.getTestSpec().getGlobalConfig();
    this.httpClient = new LoadHttpClient(
            globalConfig.getBaseUrl(),
            globalConfig.getTimeouts().getConnectionTimeoutMs() / 1000,
            globalConfig.getHeaders(),
            globalConfig.getVars()
    );
  }

  /**
   * Executes all requests across all scenarios.
   */
  public void executeAllRequests(TestPlanSpec testPlanSpec, TestPhaseManager phaseManager,
                                 int userId, boolean isWarmup, Semaphore concurrencyLimiter,
                                 AtomicBoolean cancelled) {
    try {
      activeRequests.incrementAndGet();

      for (var scenario : testPlanSpec.getTestSpec().getScenarios()) {
        if (!phaseManager.isTestRunning() || cancelled.get()) break;

        for (var request : scenario.getRequests()) {
          if (!phaseManager.isTestRunning() || cancelled.get()) break;
          executeRequest(request, phaseManager.getCurrentPhase(), userId, isWarmup);
        }
      }
    } finally {
      activeRequests.decrementAndGet();
      if (concurrencyLimiter != null) {
        concurrencyLimiter.release();
      }
    }
  }

  /**
   * Executes a single HTTP request and logs the execution details.
   */
  private void executeRequest(TestPlanSpec.Request request, TestPhaseManager.TestPhase phase,
                              int userId, boolean isWarmup) {
    var requestStart = Instant.now();
    boolean success = false;
    int statusCode = 0;

    try {
      var response = httpClient.execute(request);
      var requestEnd = Instant.now();
      long responseTime = Duration.between(requestStart, requestEnd).toMillis();

      statusCode = response.getStatusCode();
      success = true;

      logRequestExecution(phase, userId, request, responseTime, false, success, statusCode, isWarmup);
      log.debug("Request completed: {} ms, status: {}, user: {}", responseTime, statusCode, userId);

    } catch (Exception e) {
      var requestEnd = Instant.now();
      long responseTime = Duration.between(requestStart, requestEnd).toMillis();

      logRequestExecution(phase, userId, request, responseTime, false, success, statusCode, isWarmup);
      log.debug("Request failed: {}, user: {}", e.getMessage(), userId);
    }
  }

  private void logRequestExecution(TestPhaseManager.TestPhase phase, int userId,
                                   TestPlanSpec.Request request, long responseTime,
                                   boolean backPressured, boolean success, int statusCode, boolean isWarmup) {
    var executionLog = new RequestExecutionLog(
            Instant.now(), phase, userId, request.getMethod().name(),
            request.getPath(), responseTime, backPressured, success, statusCode
    );

    if (!isWarmup) {
      log.info("Request execution: {}", executionLog);
    } else {
      log.debug("Warmup request execution: {}", executionLog);
    }
  }

  public void logBackPressureEvent(TestPhaseManager.TestPhase phase) {
    log.debug("Back-pressure: max concurrent requests reached");
    var backPressureLog = new RequestExecutionLog(
            Instant.now(), phase, -1, "N/A", "N/A", 0, true, false, 0
    );
    log.info("Request execution: {}", backPressureLog);
  }

  public int getActiveRequestCount() {
    return activeRequests.get();
  }

  public void cleanup() {
    try {
      httpClient.close();
    } catch (Exception e) {
      log.warn("Error closing HTTP client: {}", e.getMessage());
    }
  }

  /**
   * Record for logging detailed request execution information.
   */
  public record RequestExecutionLog(
          Instant timestamp,
          TestPhaseManager.TestPhase phase,
          int userId,
          String method,
          String path,
          long durationMs,
          boolean backPressured,
          boolean success,
          int statusCode
  ) {}
}
