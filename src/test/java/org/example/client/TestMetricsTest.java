package org.example.client;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.example.client.metrics.ComprehensiveTestReport;
import org.example.client.metrics.MetricsWindow;
import org.example.client.metrics.TestMetrics;
import org.example.client.metrics.UserMetrics;
import org.example.client.metrics.UserMetricsSummary;
import org.example.dto.TestPlanSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockitoAnnotations;

/** Comprehensive unit tests for TestMetrics class covering all scenarios */
class TestMetricsTest {

  private ScheduledExecutorService scheduler;
  private TestMetrics testMetrics;
  private AutoCloseable mocks;

  @BeforeEach
  void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
    scheduler = Executors.newScheduledThreadPool(2);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (testMetrics != null) {
      testMetrics.shutdown();
    }
    scheduler.shutdown();
    scheduler.awaitTermination(1, TimeUnit.SECONDS);
    mocks.close();
  }

  @Nested
  @DisplayName("Basic Metrics Recording Tests")
  class BasicMetricsTests {

    @Test
    @DisplayName("Should record successful responses correctly")
    void shouldRecordSuccessfulResponses() {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When
      testMetrics.recordResponse(200, 100);
      testMetrics.recordResponse(201, 150);
      testMetrics.recordResponse(204, 75);

      // Then
      assertEquals(3, testMetrics.getTotalRequests().get());
      assertEquals(3, testMetrics.getSuccessfulRequests().get());
      assertEquals(0, testMetrics.getFailedRequests().get());
      assertEquals(0.0, testMetrics.getErrorRate());
      assertEquals(108.33333333333333, testMetrics.getAverageResponseTime(), 0.01);
    }

    @Test
    @DisplayName("Should record failed responses correctly")
    void shouldRecordFailedResponses() {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When
      testMetrics.recordResponse(200, 100);
      testMetrics.recordResponse(400, 150);
      testMetrics.recordResponse(500, 75);

      // Then
      assertEquals(3, testMetrics.getTotalRequests().get());
      assertEquals(1, testMetrics.getSuccessfulRequests().get());
      assertEquals(2, testMetrics.getFailedRequests().get());
      assertEquals(66.66666666666667, testMetrics.getErrorRate(), 0.01);
    }

    @Test
    @DisplayName("Should handle response time percentiles correctly")
    void shouldCalculatePercentilesCorrectly() {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When - Add responses with known distribution
      for (int i = 1; i <= 100; i++) {
        testMetrics.recordResponse(200, i); // 1ms to 100ms
      }

      // Then
      assertEquals(50.5, testMetrics.getAverageResponseTime(), 0.1);
      assertEquals(95, testMetrics.getP95ResponseTime());
      assertEquals(99, testMetrics.getP99ResponseTime());
      assertEquals(50, testMetrics.getPercentileResponseTime(50.0)); // P50
    }

    @Test
    @DisplayName("Should record errors correctly")
    void shouldRecordErrors() {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);
      RuntimeException testException = new RuntimeException("Test error");

      // When
      testMetrics.recordError("Connection failed", testException);
      testMetrics.recordError("Timeout", null, 5);

      // Then
      assertEquals(2, testMetrics.getFailedRequests().get());
      assertEquals(2, testMetrics.getTotalRequests().get());
      assertEquals(100.0, testMetrics.getErrorRate());
    }
  }

  @Nested
  @DisplayName("Windowed Metrics Tests")
  class WindowedMetricsTests {

    @Test
    @DisplayName("Should create windows every 5 seconds")
    @Timeout(15)
    void shouldCreateWindowsAutomatically() throws InterruptedException {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When
      testMetrics.recordResponse(200, 100);
      Thread.sleep(6000); // Wait for one window to complete
      testMetrics.recordResponse(200, 150);

      // Then
      await()
          .atMost(Duration.ofSeconds(2))
          .until(() -> testMetrics.getMetricsWindows().size() >= 2);

      assertTrue(testMetrics.getMetricsWindows().size() >= 1);
    }

    @Test
    @DisplayName("Should distribute requests across windows correctly")
    @Timeout(15)
    void shouldDistributeRequestsAcrossWindows() throws InterruptedException {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When
      // Add requests to first window
      for (int i = 0; i < 5; i++) {
        testMetrics.recordResponse(200, 100);
      }

      // Wait for window to rotate
      Thread.sleep(6000);

      // Add requests to second window
      for (int i = 0; i < 3; i++) {
        testMetrics.recordResponse(200, 150);
      }

      // Then
      await()
          .atMost(Duration.ofSeconds(2))
          .until(() -> testMetrics.getMetricsWindows().size() >= 2);

      // Total requests should be distributed
      assertEquals(8, testMetrics.getTotalRequests().get());
    }

    @Test
    @DisplayName("Should calculate window throughput correctly")
    @Timeout(15)
    void shouldCalculateWindowThroughput() throws InterruptedException {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When
      // Add 10 requests quickly
      for (int i = 0; i < 10; i++) {
        testMetrics.recordResponse(200, 100);
      }

      // Wait for window to complete
      Thread.sleep(6000);

      // Then
      await()
          .atMost(Duration.ofSeconds(2))
          .until(() -> testMetrics.getMetricsWindows().size() >= 1);

      MetricsWindow firstWindow = testMetrics.getMetricsWindows().peek();
      assertNotNull(firstWindow);
      assertEquals(10, firstWindow.getRequestCount());
      assertEquals(2.0, firstWindow.getThroughput(), 0.1); // 10 requests / 5 seconds = 2 req/sec
    }
  }

  @Nested
  @DisplayName("CLOSED Workload Model Tests")
  class ClosedWorkloadTests {

    @Test
    @DisplayName("Should track users in CLOSED model")
    void shouldTrackUsersInClosedModel() {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.CLOSED);

      // When
      testMetrics.userStarted(1);
      testMetrics.userStarted(2);
      testMetrics.recordResponse(200, 100, 1);
      testMetrics.recordResponse(200, 150, 2);
      testMetrics.recordResponse(200, 120, 1);
      testMetrics.userCompleted(1);

      // Then
      assertEquals(1, testMetrics.getActiveUsers().get()); // User 2 still active
      assertEquals(2, testMetrics.getUserMetrics().size());

      UserMetrics user1 = testMetrics.getUserMetrics().get(1);
      UserMetrics user2 = testMetrics.getUserMetrics().get(2);

      assertNotNull(user1);
      assertNotNull(user2);
      assertEquals(2, user1.getRequestCount());
      assertEquals(1, user2.getRequestCount());
      assertEquals(110.0, user1.getAverageResponseTime());
      assertEquals(150.0, user2.getAverageResponseTime());
    }

    @Test
    @DisplayName("Should not track users in OPEN model")
    void shouldNotTrackUsersInOpenModel() {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When
      testMetrics.userStarted(1);
      testMetrics.recordResponse(200, 100, 1);

      // Then
      assertEquals(0, testMetrics.getActiveUsers().get());
      assertTrue(testMetrics.getUserMetrics().isEmpty());
    }

    @Test
    @DisplayName("Should track user errors in CLOSED model")
    void shouldTrackUserErrorsInClosedModel() {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.CLOSED);

      // When
      testMetrics.userStarted(1);
      testMetrics.recordResponse(500, 100, 1);
      testMetrics.recordError("User error", new RuntimeException(), 1);

      // Then
      UserMetrics user1 = testMetrics.getUserMetrics().get(1);
      assertEquals(2, user1.getErrorCount()); // One from 500 response, one from explicit error
    }
  }

  @Nested
  @DisplayName("SLA Violation Tests")
  class SLAViolationTests {

    @Test
    @DisplayName("Should record SLA violations correctly")
    void shouldRecordSLAViolations() {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When
      testMetrics.recordSLAViolation("ERROR_RATE", 5.5, 5.0, "STOP");
      testMetrics.recordSLAViolation("P95_RESPONSE_TIME", 2000, 1500, "CONTINUE");

      // Then
      assertEquals(2, testMetrics.getSlaViolations().size());
    }

    @Test
    @DisplayName("Should track SLA violations in windows")
    @Timeout(10)
    void shouldTrackSLAViolationsInWindows() throws InterruptedException {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When
      testMetrics.recordSLAViolation("ERROR_RATE", 10.0, 5.0, "STOP");
      Thread.sleep(6000); // Wait for window rotation

      // Then
      await()
          .atMost(Duration.ofSeconds(2))
          .until(() -> testMetrics.getMetricsWindows().size() >= 1);

      MetricsWindow firstWindow = testMetrics.getMetricsWindows().peek();
      assertEquals(1, firstWindow.getSlaViolationCount());
    }
  }

  @Nested
  @DisplayName("Back Pressure Tests")
  class BackPressureTests {

    @Test
    @DisplayName("Should record back pressure events")
    void shouldRecordBackPressureEvents() {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When
      testMetrics.incrementBackPressureEvents();
      testMetrics.incrementBackPressureEvents();
      testMetrics.incrementBackPressureEvents();

      // Then
      assertEquals(3, testMetrics.getBackPressureEvents().get());
    }

    @Test
    @DisplayName("Should track back pressure in windows")
    @Timeout(10)
    void shouldTrackBackPressureInWindows() throws InterruptedException {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When
      testMetrics.incrementBackPressureEvents();
      testMetrics.incrementBackPressureEvents();
      Thread.sleep(6000); // Wait for window rotation

      // Then
      await()
          .atMost(Duration.ofSeconds(2))
          .until(() -> testMetrics.getMetricsWindows().size() >= 1);

      MetricsWindow firstWindow = testMetrics.getMetricsWindows().peek();
      assertEquals(2, firstWindow.getBackPressureEvents());
    }
  }

  @Nested
  @DisplayName("Comprehensive Report Tests")
  class ComprehensiveReportTests {

    @Test
    @DisplayName("Should generate comprehensive report for OPEN model")
    @Timeout(15)
    void shouldGenerateReportForOpenModel() throws InterruptedException {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When
      // Add various responses
      testMetrics.recordResponse(200, 100);
      testMetrics.recordResponse(201, 150);
      testMetrics.recordResponse(400, 200);
      testMetrics.recordResponse(500, 75);

      // Add errors
      testMetrics.recordError("Test error", new RuntimeException());

      // Add SLA violations
      testMetrics.recordSLAViolation("ERROR_RATE", 60.0, 50.0, "CONTINUE");

      // Add back pressure
      testMetrics.incrementBackPressureEvents();

      Thread.sleep(6000); // Wait for window

      Instant endTime = Instant.now();
      ComprehensiveTestReport report =
          testMetrics.generateComprehensiveReport(endTime, "TEST_COMPLETED");

      // Then
      assertNotNull(report);
      assertEquals(5, report.getTotalRequests()); // 4 responses + 1 error
      assertEquals(2, report.getSuccessfulRequests());
      assertEquals(3, report.getFailedRequests());
      assertEquals(60.0, report.getErrorRate());

      // SLA violations
      assertEquals(1, report.getSlaViolationSummary().getTotalViolations());

      // Back pressure
      assertEquals(1, report.getBackPressureEvents());

      // Window summaries
      assertFalse(report.getWindowSummaries().isEmpty());

      // User metrics should be null for OPEN model
      assertNull(report.getUserMetricsSummary());
    }

    @Test
    @DisplayName("Should generate comprehensive report for CLOSED model with users")
    @Timeout(15)
    void shouldGenerateReportForClosedModelWithUsers() throws InterruptedException {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.CLOSED);

      // When
      // Start users
      testMetrics.userStarted(1);
      testMetrics.userStarted(2);

      // Add user requests
      testMetrics.recordResponse(200, 100, 1);
      testMetrics.recordResponse(200, 150, 1);
      testMetrics.recordResponse(201, 120, 2);

      // Complete users
      testMetrics.userCompleted(1);
      testMetrics.userCompleted(2);

      Thread.sleep(6000); // Wait for window

      Instant endTime = Instant.now();
      ComprehensiveTestReport report =
          testMetrics.generateComprehensiveReport(endTime, "ALL_USERS_COMPLETED");

      // Then
      assertNotNull(report);
      assertEquals(3, report.getTotalRequests());
      assertEquals(3, report.getSuccessfulRequests());
      assertEquals(0, report.getFailedRequests());

      // User metrics should be present for CLOSED model
      UserMetricsSummary userSummary = report.getUserMetricsSummary();
      assertNotNull(userSummary);
      assertEquals(2, userSummary.getTotalUsers());
      assertEquals(2, userSummary.getUserPerformances().size());

      // Check individual user performance
      UserPerformance user1 =
          userSummary.getUserPerformances().stream()
              .filter(u -> u.getUserId() == 1)
              .findFirst()
              .orElse(null);
      assertNotNull(user1);
      assertEquals(2, user1.getRequestCount());
      assertEquals(125.0, user1.getAverageResponseTime());

      UserPerformance user2 =
          userSummary.getUserPerformances().stream()
              .filter(u -> u.getUserId() == 2)
              .findFirst()
              .orElse(null);
      assertNotNull(user2);
      assertEquals(1, user2.getRequestCount());
      assertEquals(120.0, user2.getAverageResponseTime());
    }

    @Test
    @DisplayName("Should include window summaries in report")
    @Timeout(15)
    void shouldIncludeWindowSummariesInReport() throws InterruptedException {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.CLOSED);

      // When
      // First window
      testMetrics.userStarted(1);
      testMetrics.recordResponse(200, 100, 1);
      testMetrics.recordResponse(200, 150, 1);

      Thread.sleep(6000); // Wait for first window

      // Second window
      testMetrics.userStarted(2);
      testMetrics.recordResponse(201, 120, 2);

      Thread.sleep(6000); // Wait for second window

      Instant endTime = Instant.now();
      ComprehensiveTestReport report =
          testMetrics.generateComprehensiveReport(endTime, "DURATION_COMPLETED");

      // Then
      List<WindowSummary> windowSummaries = report.getWindowSummaries();
      assertNotNull(windowSummaries);
      assertTrue(windowSummaries.size() >= 2);

      // Check first window
      WindowSummary firstWindow =
          windowSummaries.stream().filter(w -> w.getRequestCount() > 0).findFirst().orElse(null);
      assertNotNull(firstWindow);
      assertEquals(2, firstWindow.getRequestCount());
      assertEquals(125.0, firstWindow.getAverageResponseTime());
      assertTrue(firstWindow.getUsersStarted().contains(1));
    }
  }

  @Nested
  @DisplayName("Status Code Distribution Tests")
  class StatusCodeDistributionTests {

    @Test
    @DisplayName("Should track status code distribution correctly")
    void shouldTrackStatusCodeDistribution() {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When
      testMetrics.recordResponse(200, 100);
      testMetrics.recordResponse(200, 150);
      testMetrics.recordResponse(201, 120);
      testMetrics.recordResponse(400, 200);
      testMetrics.recordResponse(500, 180);
      testMetrics.recordResponse(500, 190);

      // Then
      var distribution = testMetrics.getStatusCodeDistribution();
      assertEquals(2L, distribution.get(200));
      assertEquals(1L, distribution.get(201));
      assertEquals(1L, distribution.get(400));
      assertEquals(2L, distribution.get(500));
    }
  }

  @Nested
  @DisplayName("Thread Safety Tests")
  class ThreadSafetyTests {

    @Test
    @DisplayName("Should handle concurrent requests safely")
    @Timeout(10)
    void shouldHandleConcurrentRequestsSafely() throws InterruptedException {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);
      int threadCount = 10;
      int requestsPerThread = 100;
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(threadCount);

      // When
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        new Thread(
                () -> {
                  try {
                    startLatch.await();
                    for (int j = 0; j < requestsPerThread; j++) {
                      testMetrics.recordResponse(200, 100 + threadId);
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  } finally {
                    doneLatch.countDown();
                  }
                })
            .start();
      }

      startLatch.countDown();
      doneLatch.await();

      // Then
      assertEquals(threadCount * requestsPerThread, testMetrics.getTotalRequests().get());
      assertEquals(threadCount * requestsPerThread, testMetrics.getSuccessfulRequests().get());
      assertEquals(0, testMetrics.getFailedRequests().get());
    }

    @Test
    @DisplayName("Should handle concurrent user operations safely in CLOSED model")
    @Timeout(10)
    void shouldHandleConcurrentUserOperationsSafely() throws InterruptedException {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.CLOSED);
      int userCount = 50;
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(userCount);

      // When
      for (int i = 0; i < userCount; i++) {
        final int userId = i;
        new Thread(
                () -> {
                  try {
                    startLatch.await();
                    testMetrics.userStarted(userId);
                    testMetrics.recordResponse(200, 100, userId);
                    testMetrics.recordResponse(201, 150, userId);
                    testMetrics.userCompleted(userId);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  } finally {
                    doneLatch.countDown();
                  }
                })
            .start();
      }

      startLatch.countDown();
      doneLatch.await();

      // Then
      assertEquals(0, testMetrics.getActiveUsers().get()); // All users completed
      assertEquals(userCount, testMetrics.getUserMetrics().size());
      assertEquals(userCount * 2, testMetrics.getTotalRequests().get());

      // Verify each user has correct metrics
      for (int i = 0; i < userCount; i++) {
        UserMetrics user = testMetrics.getUserMetrics().get(i);
        assertNotNull(user);
        assertEquals(2, user.getRequestCount());
        assertEquals(125.0, user.getAverageResponseTime());
      }
    }
  }

  @Nested
  @DisplayName("Edge Case Tests")
  class EdgeCaseTests {

    @Test
    @DisplayName("Should handle zero requests gracefully")
    void shouldHandleZeroRequestsGracefully() {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When - No requests added

      // Then
      assertEquals(0, testMetrics.getTotalRequests().get());
      assertEquals(0.0, testMetrics.getErrorRate());
      assertEquals(0.0, testMetrics.getAverageResponseTime());
      assertEquals(0, testMetrics.getP95ResponseTime());
    }

    @Test
    @DisplayName("Should handle large response times correctly")
    void shouldHandleLargeResponseTimes() {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When
      testMetrics.recordResponse(200, 10000); // 10 second response
      testMetrics.recordResponse(200, 50000); // 50 second response

      // Then
      assertEquals(2, testMetrics.getTotalRequests().get());
      assertEquals(30000.0, testMetrics.getAverageResponseTime());
    }

    @Test
    @DisplayName("Should trim response times queue when it gets too large")
    void shouldTrimResponseTimesQueue() {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When - Add more than 10000 responses
      for (int i = 0; i < 15000; i++) {
        testMetrics.recordResponse(200, 100);
      }

      // Then
      assertEquals(15000, testMetrics.getTotalRequests().get());
      assertTrue(testMetrics.getResponseTimes().size() <= 10000);
    }

    @Test
    @DisplayName("Should handle null throwable in error recording")
    void shouldHandleNullThrowableInErrorRecording() {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When
      testMetrics.recordError("Test error", null);

      // Then
      assertEquals(1, testMetrics.getFailedRequests().get());
      assertEquals(1, testMetrics.getErrors().size());
      assertEquals("Unknown", testMetrics.getErrors().peek().getExceptionType());
    }
  }

  @Nested
  @DisplayName("Phase Management Tests")
  class PhaseManagementTests {

    @Test
    @DisplayName("Should track phase changes correctly")
    void shouldTrackPhaseChanges() {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When
      testMetrics.setPhase(TestPhase.WARMUP);
      testMetrics.setPhase(TestPhase.RAMP_UP);
      testMetrics.setPhase(TestPhase.HOLD);
      testMetrics.setPhase(TestPhase.COMPLETED);

      // Then
      assertEquals(TestPhase.COMPLETED, testMetrics.getCurrentPhase().get());
    }
  }

  @Nested
  @DisplayName("Throughput Calculation Tests")
  class ThroughputTests {

    @Test
    @DisplayName("Should calculate current throughput correctly")
    void shouldCalculateCurrentThroughputCorrectly() throws InterruptedException {
      // Given
      testMetrics = new TestMetrics(scheduler, TestPlanSpec.WorkLoadModel.OPEN);

      // When
      for (int i = 0; i < 10; i++) {
        testMetrics.recordResponse(200, 100);
      }
      Thread.sleep(1100); // Wait just over 1 second

      // Then
      double throughput = testMetrics.getCurrentThroughput();
      assertTrue(throughput >= 8.0 && throughput <= 12.0); // Should be around 10 req/sec
    }
  }
}
