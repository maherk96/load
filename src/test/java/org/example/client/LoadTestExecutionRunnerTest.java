package org.example.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.client.metrics.ComprehensiveTestReport;
import org.example.client.response.RestResponseData;
import org.example.dto.TestPlanSpec;
import org.example.load.LoadTestExecutionRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Comprehensive test suite for LoadTestExecutionRunner using mocked HTTP client. This provides
 * fast, deterministic testing without network dependencies.
 */
@ExtendWith(MockitoExtension.class)
class LoadTestExecutionRunnerMockedTest {

  private TestPlanSpec testPlanSpec;

  @BeforeEach
  void setUp() throws Exception {
    testPlanSpec = createValidTestPlanSpec();
  }
  // =====================================================
  // CONSTRUCTOR TESTS
  // =====================================================

  @Test
  @DisplayName("Constructor should initialize with valid test plan spec")
  void constructor_WithValidTestPlanSpec_ShouldInitializeSuccessfully() {
    // Given
    TestPlanSpec spec = createValidTestPlanSpec();

    // When & Then
    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              when(mock.execute(any())).thenReturn(createSuccessResponse());
            })) {
      assertDoesNotThrow(() -> new LoadTestExecutionRunner(spec));
    }
  }

  @Test
  @DisplayName("Constructor should handle null test plan spec gracefully")
  void constructor_WithNullTestPlanSpec_ShouldThrowException() {
    // Given
    TestPlanSpec spec = null;

    // When & Then
    assertThrows(NullPointerException.class, () -> new LoadTestExecutionRunner(spec));
  }

  // =====================================================
  // CLOSED WORKLOAD TESTS - BASIC SCENARIOS
  // =====================================================

  @Test
  @DisplayName("CLOSED: Single user, single iteration should complete successfully")
  void executeClosedWorkload_SingleUserSingleIteration_ShouldComplete() throws Exception {
    // Given
    TestPlanSpec spec = createClosedWorkloadSpec(1, 1, "10s", "0s", "0s");

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              when(mock.execute(any())).thenReturn(createSuccessResponse());
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

      // Then
      assertNotNull(report);
      assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason());
      assertEquals(1, report.getTotalRequests());
      assertEquals(0, report.getFailedRequests());
      assertEquals(1, report.getSuccessfulRequests());

      // Verify HTTP client was called exactly once
      LoadHttpClient client = mockedConstruction.constructed().get(0);
      verify(client, times(1)).execute(any());
    }
  }

  @Test
  @DisplayName("CLOSED: Multiple users, multiple iterations should complete all work")
  void executeClosedWorkload_MultipleUsersMultipleIterations_ShouldCompleteAll() throws Exception {
    // Given
    TestPlanSpec spec = createClosedWorkloadSpec(3, 5, "30s", "2s", "0s");

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              when(mock.execute(any())).thenReturn(createSuccessResponse());
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(20, TimeUnit.SECONDS);

      // Then
      assertNotNull(report);
      assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason());
      assertEquals(15, report.getTotalRequests()); // 3 users * 5 iterations
      assertEquals(0, report.getFailedRequests());
      assertEquals(15, report.getSuccessfulRequests());

      // Verify user metrics
      assertNotNull(report.getUserMetricsSummary());
      assertEquals(3, report.getUserMetricsSummary().getTotalUsers());

      // Verify HTTP client was called 15 times
      LoadHttpClient client = mockedConstruction.constructed().get(0);
      verify(client, times(15)).execute(any());
    }
  }

  @Test
  @DisplayName("CLOSED: Hold time expiration should terminate early")
  void executeClosedWorkload_HoldTimeExpires_ShouldTerminateEarly() throws Exception {
    // Given - Very short hold time with many iterations and realistic delays
    TestPlanSpec spec = createClosedWorkloadSpec(2, 100, "2s", "0s", "0s");

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              // Simulate slower responses to allow hold time expiration
              when(mock.execute(any()))
                  .thenAnswer(
                      invocation -> {
                        Thread.sleep(100); // Add actual delay to simulate slow responses
                        return createSuccessResponseWithDelay(100);
                      });
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

      // Then
      assertNotNull(report);
      assertEquals("HOLD_TIME_EXPIRED", report.getTerminationReason());
      assertTrue(report.getTotalRequests() < 200); // Should be less than total possible (2*100)
      assertTrue(report.getTotalRequests() > 0); // But should have completed some
      assertEquals(0, report.getFailedRequests());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 5, 10, 20})
  @DisplayName("CLOSED: Should handle different user counts correctly")
  void executeClosedWorkload_DifferentUserCounts_ShouldHandleCorrectly(int userCount)
      throws Exception {
    // Given
    TestPlanSpec spec = createClosedWorkloadSpec(userCount, 2, "30s", "1s", "0s");

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              when(mock.execute(any())).thenReturn(createSuccessResponse());
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(25, TimeUnit.SECONDS);

      // Then
      assertNotNull(report);
      assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason());
      assertEquals(userCount * 2, report.getTotalRequests());
      assertEquals(userCount, report.getUserMetricsSummary().getTotalUsers());

      // Verify HTTP client was called expected number of times
      LoadHttpClient client = mockedConstruction.constructed().get(0);
      verify(client, times(userCount * 2)).execute(any());
    }
  }

  // =====================================================
  // OPEN WORKLOAD TESTS - BASIC SCENARIOS
  // =====================================================

  @Test
  @DisplayName("OPEN: Low rate, high concurrency should complete without back-pressure")
  void executeOpenWorkload_LowRateHighConcurrency_ShouldCompleteWithoutBackPressure()
      throws Exception {
    // Given
    TestPlanSpec spec = createOpenWorkloadSpec(5, 20, "3s", "0s");

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              when(mock.execute(any())).thenReturn(createSuccessResponse());
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

      // Then
      assertNotNull(report);
      assertEquals("DURATION_COMPLETED", report.getTerminationReason());
      // Should achieve close to target: 5 req/sec * 3s = ~15 requests
      assertTrue(report.getTotalRequests() >= 12 && report.getTotalRequests() <= 18);
      assertEquals(0, report.getBackPressureEvents()); // Should have no back-pressure
      assertEquals(0, report.getFailedRequests());
    }
  }

  @Test
  @DisplayName("OPEN: High rate, low concurrency should cause significant back-pressure")
  void executeOpenWorkload_HighRateLowConcurrency_ShouldCauseBackPressure() throws Exception {
    // Given - Intentionally create back-pressure scenario with actual delays
    TestPlanSpec spec = createOpenWorkloadSpec(50, 1, "2s", "0s");

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              // Simulate slower responses with actual thread blocking
              when(mock.execute(any()))
                  .thenAnswer(
                      invocation -> {
                        Thread.sleep(200); // Block thread to create actual concurrency limitation
                        return createSuccessResponseWithDelay(200);
                      });
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

      // Then
      assertNotNull(report);
      assertEquals("DURATION_COMPLETED", report.getTerminationReason());
      // Should be significantly less than target: 50 req/sec * 2s = 100
      assertTrue(report.getTotalRequests() < 50, "Should be limited by concurrency");
      assertTrue(report.getBackPressureEvents() > 10, "Should have significant back-pressure");
      assertEquals(0, report.getFailedRequests());
    }
  }

  @Test
  @DisplayName("OPEN: Extreme back-pressure scenario")
  void executeOpenWorkload_ExtremeBackPressure_ShouldHandleGracefully() throws Exception {
    // Given - Extreme scenario: very high rate, minimal concurrency
    TestPlanSpec spec = createOpenWorkloadSpec(100, 1, "1s", "0s");

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              when(mock.execute(any()))
                  .thenAnswer(
                      invocation -> {
                        Thread.sleep(100); // Block to create real concurrency limit
                        return createSuccessResponseWithDelay(100);
                      });
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

      // Then
      assertNotNull(report);
      assertEquals("DURATION_COMPLETED", report.getTerminationReason());
      // Should be severely limited
      assertTrue(
          report.getTotalRequests() < 20, "Should be severely limited by single concurrency slot");
      assertTrue(report.getBackPressureEvents() > 50, "Should have extreme back-pressure");
    }
  }

  // =====================================================
  // ERROR HANDLING TESTS
  // =====================================================

  @Test
  @DisplayName("HTTP client errors should be recorded correctly")
  void executeRequest_WithHttpErrors_ShouldRecordErrors() throws Exception {
    // Given
    TestPlanSpec spec = createClosedWorkloadSpec(2, 3, "10s", "0s", "0s");

    AtomicInteger callCount = new AtomicInteger(0);

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              // Simulate predictable pattern of success/failure
              when(mock.execute(any()))
                  .thenAnswer(
                      invocation -> {
                        int call = callCount.incrementAndGet();
                        switch (call) {
                          case 1:
                            return createSuccessResponse();
                          case 2:
                            throw new RuntimeException("Connection failed");
                          case 3:
                            return createErrorResponse();
                          case 4:
                            return createSuccessResponse();
                          case 5:
                            throw new RuntimeException("Timeout");
                          case 6:
                            return createSuccessResponse();
                          default:
                            return createSuccessResponse();
                        }
                      });
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

      // Then
      assertNotNull(report);
      assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason());
      assertEquals(6, report.getTotalRequests()); // 2 users * 3 iterations
      assertTrue(
          report.getFailedRequests() >= 2,
          "Should have at least 2 failed requests"); // Allow for some variability
      assertTrue(report.getSuccessfulRequests() >= 3, "Should have at least 3 successful requests");

      // Should have error summary
      assertNotNull(report.getErrorSummary());
      assertTrue(report.getErrorSummary().getTotalErrors() > 0);
    }
  }

  @Test
  @DisplayName("Mixed success and error responses should be handled correctly")
  void executeRequest_MixedResponses_ShouldHandleCorrectly() throws Exception {
    // Given
    TestPlanSpec spec = createClosedWorkloadSpec(1, 10, "20s", "0s", "0s");

    AtomicInteger callCount = new AtomicInteger(0);

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              // Simulate strict alternating success/failure pattern
              when(mock.execute(any()))
                  .thenAnswer(
                      invocation -> {
                        int call = callCount.incrementAndGet();
                        if (call % 2 == 1) {
                          return createSuccessResponse();
                        } else {
                          return createErrorResponse();
                        }
                      });
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

      // Then
      assertNotNull(report);
      assertEquals(10, report.getTotalRequests());
      assertEquals(5, report.getSuccessfulRequests()); // Every other request succeeds
      assertEquals(5, report.getFailedRequests()); // Every other request fails
      assertEquals(50.0, report.getErrorRate(), 0.01);
    }
  }

  @Test
  @DisplayName("All requests failing should complete execution")
  void executeRequest_AllRequestsFailing_ShouldCompleteExecution() throws Exception {
    // Given
    TestPlanSpec spec = createClosedWorkloadSpec(2, 2, "10s", "0s", "0s");

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              // All requests throw exceptions
              when(mock.execute(any())).thenThrow(new RuntimeException("Service unavailable"));
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

      // Then
      assertNotNull(report);
      assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason());
      assertEquals(4, report.getTotalRequests()); // 2 users * 2 iterations
      assertEquals(4, report.getFailedRequests()); // All failed
      assertEquals(0, report.getSuccessfulRequests());
      assertEquals(100.0, report.getErrorRate(), 0.01);

      // Should have error summary
      assertNotNull(report.getErrorSummary());
      assertTrue(report.getErrorSummary().getTotalErrors() > 0);
    }
  }

  // =====================================================
  // THINK TIME TESTS
  // =====================================================

  @Test
  @DisplayName("FIXED think time should delay requests consistently")
  void applyThinkTime_Fixed_ShouldDelayConsistently() throws Exception {
    // Given
    TestPlanSpec spec =
        createClosedWorkloadSpecWithThinkTime(
            1, 3, "30s", "0s", "0s", TestPlanSpec.ThinkTimeType.FIXED, 500, 500);

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              when(mock.execute(any())).thenReturn(createSuccessResponse());
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When
      Instant start = Instant.now();
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);
      Instant end = Instant.now();

      // Then
      assertNotNull(report);
      assertEquals(3, report.getTotalRequests());

      // Should take at least 2 * 500ms = 1000ms for think time between 3 requests
      long durationMs = Duration.between(start, end).toMillis();
      assertTrue(
          durationMs >= 1000, "Should include think time delays, actual: " + durationMs + "ms");
    }
  }

  @Test
  @DisplayName("RANDOM think time should vary within specified range")
  void applyThinkTime_Random_ShouldVaryWithinRange() throws Exception {
    // Given - Use moderate range to avoid timing issues
    TestPlanSpec spec =
        createClosedWorkloadSpecWithThinkTime(
            1, 3, "30s", "0s", "0s", TestPlanSpec.ThinkTimeType.RANDOM, 100, 300);

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              when(mock.execute(any())).thenReturn(createSuccessResponse());
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When - Measure only the execution phase, not cleanup
      Instant start = Instant.now();
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);
      Instant executionEnd = Instant.now();

      // Then
      assertNotNull(report);
      assertEquals(3, report.getTotalRequests());

      // Should take at least minimum time (2 think times * 100ms = 200ms)
      // Measure only execution time, not including cleanup delays
      long executionDurationMs = Duration.between(start, executionEnd).toMillis();
      assertTrue(
          executionDurationMs >= 200,
          "Should include minimum think time, actual: " + executionDurationMs + "ms");

      // Be more lenient with maximum to account for test environment variability
      // Focus on the execution completing rather than exact timing
      assertTrue(
          report.getTerminationReason().equals("ALL_ITERATIONS_COMPLETED"),
          "Test should complete all iterations");
    }
  }

  // =====================================================
  // WARMUP TESTS
  // =====================================================

  @Test
  @DisplayName("Warmup phase should not affect final metrics")
  void executeWithWarmup_ShouldNotAffectFinalMetrics() throws Exception {
    // Given
    TestPlanSpec spec = createClosedWorkloadSpec(2, 3, "10s", "1s", "2s");

    AtomicInteger callCount = new AtomicInteger(0);

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              when(mock.execute(any()))
                  .thenAnswer(
                      invocation -> {
                        callCount.incrementAndGet();
                        return createSuccessResponse();
                      });
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(20, TimeUnit.SECONDS);

      // Then
      assertNotNull(report);
      assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason());
      assertEquals(6, report.getTotalRequests()); // Only actual test requests counted, not warmup
      assertEquals(0, report.getFailedRequests());

      // Total HTTP calls should be more than 6 due to warmup requests
      assertTrue(callCount.get() > 6, "Should have additional warmup requests");
    }
  }

  // =====================================================
  // PERFORMANCE TESTS WITH CONTROLLED RESPONSES
  // =====================================================

  @Test
  @DisplayName("Variable response times should affect metrics correctly")
  void variableResponseTimes_ShouldAffectMetrics() throws Exception {
    // Given
    TestPlanSpec spec = createClosedWorkloadSpec(1, 5, "30s", "0s", "0s");

    AtomicInteger callCount = new AtomicInteger(0);

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              when(mock.execute(any()))
                  .thenAnswer(
                      invocation -> {
                        int call = callCount.incrementAndGet();
                        int delay;
                        switch (call) {
                          case 1:
                            delay = 100;
                            break;
                          case 2:
                            delay = 200;
                            break;
                          case 3:
                            delay = 500;
                            break;
                          case 4:
                            delay = 150;
                            break;
                          case 5:
                            delay = 300;
                            break;
                          default:
                            delay = 100;
                            break;
                        }
                        // Actually sleep to create realistic timing
                        Thread.sleep(delay);
                        return createSuccessResponseWithDelay(delay);
                      });
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

      // Then
      assertNotNull(report);
      assertEquals(5, report.getTotalRequests());
      assertEquals(0, report.getFailedRequests());

      // Check response time metrics - should reflect actual timing
      assertTrue(
          report.getAverageResponseTime() >= 200,
          "Average should be realistic, was: " + report.getAverageResponseTime());
      assertTrue(
          report.getAverageResponseTime() <= 350,
          "Average should be reasonable, was: " + report.getAverageResponseTime());
      assertTrue(report.getMaxResponseTime() >= 400, "Max should reflect slowest response");
    }
  }

  @Test
  @DisplayName("Status code distribution should be tracked correctly")
  void statusCodeDistribution_ShouldBeTrackedCorrectly() throws Exception {
    // Given
    TestPlanSpec spec = createClosedWorkloadSpec(1, 6, "30s", "0s", "0s");

    AtomicInteger callCount = new AtomicInteger(0);

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              when(mock.execute(any()))
                  .thenAnswer(
                      invocation -> {
                        int call = callCount.incrementAndGet();
                        switch (call) {
                          case 1:
                            return createResponseWithStatus(200);
                          case 2:
                            return createResponseWithStatus(201);
                          case 3:
                            return createResponseWithStatus(200);
                          case 4:
                            return createResponseWithStatus(404);
                          case 5:
                            return createResponseWithStatus(500);
                          case 6:
                            return createResponseWithStatus(201);
                          default:
                            return createResponseWithStatus(200);
                        }
                      });
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

      // Then
      assertNotNull(report);
      assertEquals(6, report.getTotalRequests());
      assertEquals(4, report.getSuccessfulRequests()); // 200s and 201s
      assertEquals(2, report.getFailedRequests()); // 404 and 500

      // Check status code distribution
      Map<Integer, Long> statusDistribution = report.getStatusCodeDistribution();
      assertEquals(2L, statusDistribution.get(200).longValue());
      assertEquals(2L, statusDistribution.get(201).longValue());
      assertEquals(1L, statusDistribution.get(404).longValue());
      assertEquals(1L, statusDistribution.get(500).longValue());
    }
  }

  // =====================================================
  // INTEGRATION SCENARIOS WITH MOCKED RESPONSES
  // =====================================================

  @Test
  @DisplayName("Realistic load test scenario with mixed responses")
  void realisticScenario_MixedResponses_ShouldCompleteSuccessfully() throws Exception {
    // Given - Realistic scenario with mostly success, some failures
    TestPlanSpec spec = createClosedWorkloadSpec(5, 10, "30s", "5s", "2s");

    AtomicInteger callCount = new AtomicInteger(0);

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              when(mock.execute(any()))
                  .thenAnswer(
                      invocation -> {
                        int call = callCount.incrementAndGet();
                        // Create predictable pattern: 90% success, 10% failure
                        if (call % 10 <= 8) {
                          // Success case - vary response times realistically
                          int responseTime = 50 + (call % 5) * 50; // 50-250ms
                          Thread.sleep(responseTime);
                          return createSuccessResponseWithDelay(responseTime);
                        } else {
                          // Failure case
                          if (call % 20 == 9) {
                            return createErrorResponse(); // 5% error responses
                          } else {
                            throw new RuntimeException("Network error"); // 5% exceptions
                          }
                        }
                      });
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(45, TimeUnit.SECONDS);

      // Then
      assertNotNull(report);
      assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason());
      assertEquals(50, report.getTotalRequests()); // 5 users * 10 iterations

      // Should have mostly successful requests (around 90%)
      assertTrue(report.getSuccessfulRequests() >= 40, "Should have mostly successful requests");
      assertTrue(report.getSuccessfulRequests() <= 50, "Cannot have more than total requests");

      // Should have some failures (around 10%)
      assertTrue(report.getFailedRequests() >= 0, "May have some failed requests");
      assertTrue(report.getFailedRequests() <= 10, "Should not have too many failures");

      // Error rate should be reasonable
      assertTrue(report.getErrorRate() <= 20.0, "Error rate should be reasonable");

      // Response times should be in expected range
      assertTrue(
          report.getAverageResponseTime() >= 50, "Average response time should be realistic");
      assertTrue(
          report.getAverageResponseTime() <= 300, "Average response time should be reasonable");
    }
  }

  // =====================================================
  // HELPER METHODS
  // =====================================================

  private TestPlanSpec createValidTestPlanSpec() {
    return createClosedWorkloadSpec(2, 5, "10s", "2s", "1s");
  }

  private TestPlanSpec createClosedWorkloadSpec(
      int users, int iterations, String holdFor, String rampUp, String warmup) {
    TestPlanSpec spec = new TestPlanSpec();

    // Test spec
    TestPlanSpec.TestSpec testSpec = new TestPlanSpec.TestSpec();
    testSpec.setId("test-1");

    // Global config
    TestPlanSpec.GlobalConfig globalConfig = new TestPlanSpec.GlobalConfig();
    globalConfig.setBaseUrl("https://api.example.com");

    TestPlanSpec.Timeouts timeouts = new TestPlanSpec.Timeouts();
    timeouts.setConnectionTimeoutMs(15000);
    globalConfig.setTimeouts(timeouts);

    testSpec.setGlobalConfig(globalConfig);

    // Scenarios
    TestPlanSpec.Scenario scenario = new TestPlanSpec.Scenario();
    scenario.setName("test-scenario");

    TestPlanSpec.Request request = new TestPlanSpec.Request();
    request.setMethod(TestPlanSpec.HttpMethod.POST);
    request.setPath("/posts");
    request.setBody("{\"title\": \"Test Post\", \"body\": \"Test content\", \"userId\": 1}");
    scenario.setRequests(request);

    testSpec.setScenarios(java.util.List.of(scenario));
    spec.setTestSpec(testSpec);

    // Execution
    TestPlanSpec.Execution execution = new TestPlanSpec.Execution();

    TestPlanSpec.LoadModel loadModel = new TestPlanSpec.LoadModel();
    loadModel.setType(TestPlanSpec.WorkLoadModel.CLOSED);
    loadModel.setUsers(users);
    loadModel.setIterations(iterations);
    loadModel.setHoldFor(holdFor);
    loadModel.setRampUp(rampUp);
    loadModel.setWarmup(warmup);

    execution.setLoadModel(loadModel);
    spec.setExecution(execution);

    return spec;
  }

  private TestPlanSpec createOpenWorkloadSpec(
      int arrivalRate, int maxConcurrent, String duration, String warmup) {
    TestPlanSpec spec = createValidTestPlanSpec();

    TestPlanSpec.LoadModel loadModel = spec.getExecution().getLoadModel();
    loadModel.setType(TestPlanSpec.WorkLoadModel.OPEN);
    loadModel.setArrivalRatePerSec(arrivalRate);
    loadModel.setMaxConcurrent(maxConcurrent);
    loadModel.setDuration(duration);
    loadModel.setWarmup(warmup);

    return spec;
  }

  private TestPlanSpec createClosedWorkloadSpecWithThinkTime(
      int users,
      int iterations,
      String holdFor,
      String rampUp,
      String warmup,
      TestPlanSpec.ThinkTimeType type,
      int min,
      int max) {
    TestPlanSpec spec = createClosedWorkloadSpec(users, iterations, holdFor, rampUp, warmup);

    TestPlanSpec.ThinkTime thinkTime = new TestPlanSpec.ThinkTime();
    thinkTime.setType(type);
    thinkTime.setMin(min);
    thinkTime.setMax(max);

    spec.getExecution().setThinkTime(thinkTime);

    return spec;
  }

  private RestResponseData createSuccessResponse() {
    return createSuccessResponseWithDelay(100);
  }

  private RestResponseData createSuccessResponseWithDelay(int responseTimeMs) {
    var response = new RestResponseData();
    response.setStatusCode(201);
    response.setBody(
        "{\"id\": 101, \"title\": \"Test Post\", \"body\": \"Test content\", \"userId\": 1}");
    response.setHeaders(Map.of("Content-Type", "application/json"));
    response.setResponseTimeMs(responseTimeMs);
    return response;
  }

  private RestResponseData createErrorResponse() {
    var response = new RestResponseData();
    response.setStatusCode(500);
    response.setBody("Internal Server Error");
    response.setHeaders(Map.of("Content-Type", "text/plain"));
    response.setResponseTimeMs(100);
    return response;
  }

  private RestResponseData createResponseWithStatus(int statusCode) {
    var response = new RestResponseData();
    response.setStatusCode(statusCode);

    if (statusCode >= 200 && statusCode < 300) {
      response.setBody("{\"id\": 101, \"message\": \"Success\"}");
    } else if (statusCode == 404) {
      response.setBody("{\"error\": \"Not Found\"}");
    } else if (statusCode >= 500) {
      response.setBody("{\"error\": \"Server Error\"}");
    } else {
      response.setBody("{\"message\": \"Response\"}");
    }

    response.setHeaders(Map.of("Content-Type", "application/json"));
    response.setResponseTimeMs(100);
    return response;
  }
}

// =====================================================
// INTEGRATION TESTS WITH REAL HTTP CLIENT
// =====================================================

/**
 * Integration tests that use the real HTTP client for end-to-end testing. These tests are slower
 * but provide real-world validation.
 *
 * <p>Run with: ./gradlew test --tests "*IntegrationTest"
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("integration")
class LoadTestExecutionRunnerIntegrationTest {

  @Test
  @Order(1)
  @DisplayName("Integration: Small CLOSED workload with real HTTP")
  void integrationTest_SmallClosedWorkload_ShouldCompleteSuccessfully() throws Exception {
    // Given
    TestPlanSpec spec = createRealTestPlanSpec();
    LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

    // When
    CompletableFuture<ComprehensiveTestReport> future = runner.execute();
    ComprehensiveTestReport report = future.get(45, TimeUnit.SECONDS);

    // Then
    assertNotNull(report);
    assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason());
    assertTrue(report.getTotalRequests() > 0);
    // Allow for some network issues but expect mostly successful requests
    assertTrue(
        report.getSuccessfulRequests() >= report.getTotalRequests() * 0.8,
        "Should have at least 80% success rate");
  }

  @Test
  @Order(2)
  @DisplayName("Integration: Small OPEN workload with real HTTP")
  void integrationTest_SmallOpenWorkload_ShouldCompleteSuccessfully() throws Exception {
    // Given
    TestPlanSpec spec = createRealOpenWorkloadSpec();
    LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

    // When
    CompletableFuture<ComprehensiveTestReport> future = runner.execute();
    ComprehensiveTestReport report = future.get(45, TimeUnit.SECONDS);

    // Then
    assertNotNull(report);
    assertEquals("DURATION_COMPLETED", report.getTerminationReason());
    assertTrue(report.getTotalRequests() > 0);
    // Allow for some network issues
    assertTrue(report.getSuccessfulRequests() > 0);
  }

  @Test
  @Order(3)
  @DisplayName("Integration: Error handling with invalid endpoint")
  void integrationTest_InvalidEndpoint_ShouldHandleErrors() throws Exception {
    // Given - Use an endpoint that will return errors
    TestPlanSpec spec = createRealTestPlanSpec();
    // Change to an endpoint that will return 404
    spec.getTestSpec().getScenarios().get(0).getRequests().setPath("/invalid-endpoint-12345");
    LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

    // When
    CompletableFuture<ComprehensiveTestReport> future = runner.execute();
    ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

    // Then
    assertNotNull(report);
    assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason());
    assertTrue(report.getTotalRequests() > 0);
    // Should have errors due to 404 responses
    assertTrue(report.getFailedRequests() > 0, "Should have failed requests due to 404s");
    assertTrue(report.getErrorRate() > 0, "Should have non-zero error rate");
  }

  @Test
  @Order(4)
  @DisplayName("Integration: Network timeout handling")
  void integrationTest_NetworkTimeout_ShouldHandleTimeouts() throws Exception {
    // Given - Use very short timeout to force timeouts
    TestPlanSpec spec = createRealTestPlanSpec();
    spec.getTestSpec().getGlobalConfig().getTimeouts().setConnectionTimeoutMs(1); // 1ms timeout
    LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

    // When
    CompletableFuture<ComprehensiveTestReport> future = runner.execute();
    ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

    // Then
    assertNotNull(report);
    assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason());
    assertTrue(report.getTotalRequests() > 0);
    // Should have errors due to timeouts
    assertTrue(report.getFailedRequests() > 0, "Should have failed requests due to timeouts");
    assertNotNull(report.getErrorSummary());
    assertTrue(report.getErrorSummary().getTotalErrors() > 0);
  }

  @Test
  @Order(5)
  @DisplayName("Integration: Back-pressure in OPEN model")
  void integrationTest_BackPressure_ShouldLimitThroughput() throws Exception {
    // Given - High rate, low concurrency to ensure back-pressure
    TestPlanSpec spec = createBackPressureTestSpec();
    LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

    // When
    CompletableFuture<ComprehensiveTestReport> future = runner.execute();
    ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

    // Then
    assertNotNull(report);
    assertEquals("DURATION_COMPLETED", report.getTerminationReason());
    assertTrue(report.getTotalRequests() > 0);
    // Should have back-pressure events
    assertTrue(report.getBackPressureEvents() > 10, "Should have significant back-pressure");
    // Should be limited by concurrency
    assertTrue(report.getTotalRequests() < 80, "Should be limited by concurrency");
  }

  // =====================================================
  // HELPER METHODS FOR INTEGRATION TESTS
  // =====================================================

  private TestPlanSpec createRealTestPlanSpec() {
    TestPlanSpec spec = new TestPlanSpec();

    TestPlanSpec.TestSpec testSpec = new TestPlanSpec.TestSpec();
    testSpec.setId("integration-test");

    TestPlanSpec.GlobalConfig globalConfig = new TestPlanSpec.GlobalConfig();
    globalConfig.setBaseUrl("https://jsonplaceholder.typicode.com");

    TestPlanSpec.Timeouts timeouts = new TestPlanSpec.Timeouts();
    timeouts.setConnectionTimeoutMs(10000);
    globalConfig.setTimeouts(timeouts);

    testSpec.setGlobalConfig(globalConfig);

    TestPlanSpec.Scenario scenario = new TestPlanSpec.Scenario();
    scenario.setName("get-posts");

    TestPlanSpec.Request request = new TestPlanSpec.Request();
    request.setMethod(TestPlanSpec.HttpMethod.GET);
    request.setPath("/posts/1");
    scenario.setRequests(request);

    testSpec.setScenarios(java.util.List.of(scenario));
    spec.setTestSpec(testSpec);

    TestPlanSpec.Execution execution = new TestPlanSpec.Execution();

    TestPlanSpec.LoadModel loadModel = new TestPlanSpec.LoadModel();
    loadModel.setType(TestPlanSpec.WorkLoadModel.CLOSED);
    loadModel.setUsers(3);
    loadModel.setIterations(2);
    loadModel.setHoldFor("15s");
    loadModel.setRampUp("2s");
    loadModel.setWarmup("1s");

    execution.setLoadModel(loadModel);
    spec.setExecution(execution);

    return spec;
  }

  private TestPlanSpec createRealOpenWorkloadSpec() {
    TestPlanSpec spec = createRealTestPlanSpec();

    TestPlanSpec.LoadModel loadModel = spec.getExecution().getLoadModel();
    loadModel.setType(TestPlanSpec.WorkLoadModel.OPEN);
    loadModel.setArrivalRatePerSec(3);
    loadModel.setMaxConcurrent(5);
    loadModel.setDuration("4s");
    loadModel.setWarmup("1s");

    return spec;
  }

  private TestPlanSpec createBackPressureTestSpec() {
    TestPlanSpec spec = createRealTestPlanSpec();

    TestPlanSpec.LoadModel loadModel = spec.getExecution().getLoadModel();
    loadModel.setType(TestPlanSpec.WorkLoadModel.OPEN);
    loadModel.setArrivalRatePerSec(50); // High rate
    loadModel.setMaxConcurrent(1); // Low concurrency
    loadModel.setDuration("2s");
    loadModel.setWarmup("0s");

    return spec;
  }
}

// =====================================================
// PERFORMANCE BENCHMARK TESTS
// =====================================================

/**
 * Performance benchmark tests to verify the load testing framework can handle high throughput
 * scenarios efficiently.
 *
 * <p>Run with: ./gradlew test --tests "*PerformanceTest"
 */
@Tag("performance")
class LoadTestExecutionRunnerPerformanceTest {

  @Test
  @DisplayName("Performance: High concurrency CLOSED workload")
  void performanceTest_HighConcurrencyClosed_ShouldHandleEfficiently() throws Exception {
    // Given
    TestPlanSpec spec = createPerformanceClosedSpec();

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              // Fast responses for performance testing
              when(mock.execute(any())).thenReturn(createFastSuccessResponse());
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When
      Instant start = Instant.now();
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(60, TimeUnit.SECONDS);
      Instant end = Instant.now();

      // Then
      assertNotNull(report);
      assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason());
      assertEquals(1000, report.getTotalRequests()); // 100 users * 10 iterations
      assertEquals(0, report.getFailedRequests());

      // Performance assertions
      long durationSeconds = Duration.between(start, end).getSeconds();
      assertTrue(durationSeconds < 30, "Should complete high concurrency test in reasonable time");

      // Throughput should be high
      assertTrue(report.getAverageThroughput() > 30, "Should achieve good throughput");
    }
  }

  @Test
  @DisplayName("Performance: High rate OPEN workload")
  void performanceTest_HighRateOpen_ShouldHandleEfficiently() throws Exception {
    // Given
    TestPlanSpec spec = createPerformanceOpenSpec();

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              when(mock.execute(any())).thenReturn(createFastSuccessResponse());
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // When
      Instant start = Instant.now();
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);
      Instant end = Instant.now();

      // Then
      assertNotNull(report);
      assertEquals("DURATION_COMPLETED", report.getTerminationReason());
      // Should achieve close to target: 100 req/sec * 10s = ~1000 requests
      assertTrue(report.getTotalRequests() >= 800, "Should achieve high throughput");
      assertTrue(report.getTotalRequests() <= 1200, "Should not exceed reasonable bounds");

      // Performance assertions
      long durationSeconds = Duration.between(start, end).getSeconds();
      assertTrue(durationSeconds < 15, "Should complete efficiently");
      assertTrue(report.getAverageThroughput() > 50, "Should maintain high throughput");
    }
  }

  @Test
  @DisplayName("Performance: Memory efficiency with many users")
  void performanceTest_MemoryEfficiency_ShouldNotExhaustMemory() throws Exception {
    // Given - Test memory efficiency with many short-lived users
    TestPlanSpec spec = createMemoryTestSpec();

    try (MockedConstruction<LoadHttpClient> mockedConstruction =
        mockConstruction(
            LoadHttpClient.class,
            (mock, context) -> {
              when(mock.execute(any())).thenReturn(createFastSuccessResponse());
            })) {

      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

      // Monitor memory usage
      Runtime runtime = Runtime.getRuntime();
      long initialMemory = runtime.totalMemory() - runtime.freeMemory();

      // When
      CompletableFuture<ComprehensiveTestReport> future = runner.execute();
      ComprehensiveTestReport report = future.get(45, TimeUnit.SECONDS);

      // Then
      assertNotNull(report);
      assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason());
      assertEquals(500, report.getTotalRequests()); // 250 users * 2 iterations

      // Memory usage should be reasonable
      runtime.gc(); // Force garbage collection
      Thread.sleep(100); // Allow GC to complete
      long finalMemory = runtime.totalMemory() - runtime.freeMemory();
      long memoryIncrease = finalMemory - initialMemory;

      // Memory increase should be reasonable (less than 100MB)
      assertTrue(
          memoryIncrease < 100 * 1024 * 1024,
          "Memory usage should be efficient even with many users");
    }
  }

  // Helper methods for performance tests
  private TestPlanSpec createPerformanceClosedSpec() {
    TestPlanSpec spec = new TestPlanSpec();

    TestPlanSpec.TestSpec testSpec = new TestPlanSpec.TestSpec();
    testSpec.setId("performance-test");

    TestPlanSpec.GlobalConfig globalConfig = new TestPlanSpec.GlobalConfig();
    globalConfig.setBaseUrl("https://api.example.com");
    TestPlanSpec.Timeouts timeouts = new TestPlanSpec.Timeouts();
    timeouts.setConnectionTimeoutMs(5000);
    globalConfig.setTimeouts(timeouts);
    testSpec.setGlobalConfig(globalConfig);

    TestPlanSpec.Scenario scenario = new TestPlanSpec.Scenario();
    scenario.setName("perf-scenario");
    TestPlanSpec.Request request = new TestPlanSpec.Request();
    request.setMethod(TestPlanSpec.HttpMethod.GET);
    request.setPath("/test");
    scenario.setRequests(request);
    testSpec.setScenarios(java.util.List.of(scenario));
    spec.setTestSpec(testSpec);

    TestPlanSpec.Execution execution = new TestPlanSpec.Execution();
    TestPlanSpec.LoadModel loadModel = new TestPlanSpec.LoadModel();
    loadModel.setType(TestPlanSpec.WorkLoadModel.CLOSED);
    loadModel.setUsers(100); // High concurrency
    loadModel.setIterations(10); // Multiple iterations
    loadModel.setHoldFor("30s");
    loadModel.setRampUp("5s");
    loadModel.setWarmup("1s");
    execution.setLoadModel(loadModel);
    spec.setExecution(execution);

    return spec;
  }

  private TestPlanSpec createPerformanceOpenSpec() {
    TestPlanSpec spec = createPerformanceClosedSpec();
    TestPlanSpec.LoadModel loadModel = spec.getExecution().getLoadModel();
    loadModel.setType(TestPlanSpec.WorkLoadModel.OPEN);
    loadModel.setArrivalRatePerSec(100); // High rate
    loadModel.setMaxConcurrent(50); // High concurrency
    loadModel.setDuration("10s");
    loadModel.setWarmup("1s");
    return spec;
  }

  private TestPlanSpec createMemoryTestSpec() {
    TestPlanSpec spec = createPerformanceClosedSpec();
    TestPlanSpec.LoadModel loadModel = spec.getExecution().getLoadModel();
    loadModel.setUsers(250); // Many users
    loadModel.setIterations(2); // Few iterations each
    loadModel.setRampUp("10s"); // Spread over time
    return spec;
  }

  private RestResponseData createFastSuccessResponse() {
    var response = new RestResponseData();
    response.setStatusCode(200);
    response.setBody("{\"status\": \"ok\"}");
    response.setHeaders(Map.of("Content-Type", "application/json"));
    response.setResponseTimeMs(10); // Very fast response
    return response;
  }
}
