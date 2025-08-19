package org.example.client.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.Getter;
import org.example.client.enums.TestPhase;
import org.example.dto.TestPlanSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Comprehensive Test Metrics with fixed windowed reporting and SLA tracking */
@Getter
public class TestMetrics {
  private static final Logger log = LoggerFactory.getLogger(TestMetrics.class);
  private static final int WINDOW_SIZE_SECONDS = 5;

  // Core metrics
  private final AtomicLong totalRequests = new AtomicLong(0);
  private final AtomicLong successfulRequests = new AtomicLong(0);
  private final AtomicLong failedRequests = new AtomicLong(0);
  private final AtomicLong totalResponseTime = new AtomicLong(0);
  private final AtomicInteger activeRequests = new AtomicInteger(0);
  private final AtomicInteger activeUsers = new AtomicInteger(0);
  private final AtomicLong scheduledRequests = new AtomicLong(0);
  private final AtomicLong backPressureEvents = new AtomicLong(0);

  // Test lifecycle tracking
  private final Instant testStartTime;
  private final AtomicReference<Instant> lastRequestTime = new AtomicReference<>();
  private final AtomicReference<TestPhase> currentPhase =
      new AtomicReference<>(TestPhase.INITIALIZING);

  // Response time tracking
  private final Queue<Long> responseTimes = new ConcurrentLinkedQueue<>();
  private final Map<Integer, AtomicLong> statusCodeCounts = new ConcurrentHashMap<>();

  // Error tracking
  private final Queue<ErrorInfo> errors = new ConcurrentLinkedQueue<>();

  // Windowed metrics - FIXED
  private final Queue<MetricsWindow> metricsWindows = new ConcurrentLinkedQueue<>();
  private volatile MetricsWindow currentWindow;
  private final ScheduledExecutorService scheduler;

  // SLA violation tracking
  private final Queue<SLAViolationInfo> slaViolations = new ConcurrentLinkedQueue<>();

  // User tracking for CLOSED model
  private final Map<Integer, UserMetrics> userMetrics = new ConcurrentHashMap<>();
  private final TestPlanSpec.WorkLoadModel workloadModel;

  public TestMetrics(ScheduledExecutorService scheduler, TestPlanSpec.WorkLoadModel workloadModel) {
    this.scheduler = scheduler;
    this.workloadModel = workloadModel;
    this.testStartTime = Instant.now();
    this.currentWindow = new MetricsWindow(testStartTime, WINDOW_SIZE_SECONDS);

    startWindowedReporting();
  }

  // === Core Metrics Recording ===

  public void recordResponse(int statusCode, long responseTime) {
    recordResponse(statusCode, responseTime, -1); // -1 = no specific user
  }

  public void recordResponse(int statusCode, long responseTime, int userId) {
    Instant now = Instant.now();
    lastRequestTime.set(now);

    // Core metrics
    totalRequests.incrementAndGet();
    totalResponseTime.addAndGet(responseTime);
    responseTimes.offer(responseTime);

    // Trim response times queue to prevent memory issues
    while (responseTimes.size() > 10000) {
      responseTimes.poll();
    }

    // Status code tracking
    statusCodeCounts.computeIfAbsent(statusCode, k -> new AtomicLong(0)).incrementAndGet();

    // Success/failure classification
    if (statusCode < 400) {
      successfulRequests.incrementAndGet();
    } else {
      failedRequests.incrementAndGet();
    }

    // Window metrics - FIXED: Always record in current window
    MetricsWindow window = currentWindow;
    if (window != null) {
      window.recordResponse(statusCode, responseTime, now);
    }

    // User metrics (for CLOSED model)
    if (userId >= 0 && workloadModel == TestPlanSpec.WorkLoadModel.CLOSED) {
      userMetrics
          .computeIfAbsent(userId, UserMetrics::new)
          .recordResponse(statusCode, responseTime, now);
    }

    log.debug("Response recorded: {}ms, status: {}, user: {}", responseTime, statusCode, userId);
  }

  public void recordError(String errorMessage, Throwable throwable) {
    recordError(errorMessage, throwable, -1);
  }

  public void recordError(String errorMessage, Throwable throwable, int userId) {
    Instant now = Instant.now();

    failedRequests.incrementAndGet();
    totalRequests.incrementAndGet();

    ErrorInfo error =
        ErrorInfo.builder()
            .timestamp(now)
            .message(errorMessage)
            .exceptionType(throwable != null ? throwable.getClass().getSimpleName() : "Unknown")
            .userId(userId)
            .build();

    errors.offer(error);

    // Record in current window
    MetricsWindow window = currentWindow;
    if (window != null) {
      window.recordError(error);
    }

    // User error tracking
    if (userId >= 0 && workloadModel == TestPlanSpec.WorkLoadModel.CLOSED) {
      userMetrics.computeIfAbsent(userId, UserMetrics::new).recordError(error);
    }

    // Trim errors queue
    while (errors.size() > 1000) {
      errors.poll();
    }

    log.debug("Error recorded: {}, user: {}", errorMessage, userId);
  }

  public void recordSLAViolation(
      String violationType, double actualValue, double threshold, String action) {
    Instant now = Instant.now();

    SLAViolationInfo violation =
        SLAViolationInfo.builder()
            .timestamp(now)
            .violationType(violationType)
            .actualValue(actualValue)
            .threshold(threshold)
            .action(action)
            .testPhase(currentPhase.get())
            .build();

    slaViolations.offer(violation);

    // Record in current window
    MetricsWindow window = currentWindow;
    if (window != null) {
      window.recordSLAViolation(violation);
    }

    log.warn(
        "SLA violation recorded: {} = {} > {} (action: {})",
        violationType,
        actualValue,
        threshold,
        action);
  }

  // === User Management (CLOSED Model) ===

  public void userStarted(int userId) {
    if (workloadModel == TestPlanSpec.WorkLoadModel.CLOSED) {
      activeUsers.incrementAndGet();
      userMetrics.computeIfAbsent(userId, UserMetrics::new).setStartTime(Instant.now());

      // Record in current window
      MetricsWindow window = currentWindow;
      if (window != null) {
        window.recordUserStarted(userId);
      }

      log.debug("User {} started", userId);
    }
  }

  public void userCompleted(int userId) {
    if (workloadModel == TestPlanSpec.WorkLoadModel.CLOSED) {
      activeUsers.decrementAndGet();
      UserMetrics user = userMetrics.get(userId);
      if (user != null) {
        user.setEndTime(Instant.now());
      }

      // Record in current window
      MetricsWindow window = currentWindow;
      if (window != null) {
        window.recordUserCompleted(userId);
      }

      log.debug("User {} completed", userId);
    }
  }

  // === Phase Management ===

  public void setPhase(TestPhase phase) {
    TestPhase previous = currentPhase.getAndSet(phase);
    log.info("Test phase changed: {} -> {}", previous, phase);
  }

  // === Request Lifecycle ===

  public void incrementActiveRequests() {
    activeRequests.incrementAndGet();
  }

  public void decrementActiveRequests() {
    activeRequests.decrementAndGet();
  }

  public void incrementScheduledRequests() {
    scheduledRequests.incrementAndGet();
  }

  public void incrementBackPressureEvents() {
    backPressureEvents.incrementAndGet();

    // Record in current window
    MetricsWindow window = currentWindow;
    if (window != null) {
      window.recordBackPressureEvent(Instant.now());
    }
  }

  // === Calculated Metrics ===

  public double getErrorRate() {
    long total = totalRequests.get();
    return total > 0 ? (double) failedRequests.get() / total * 100.0 : 0.0;
  }

  public double getAverageResponseTime() {
    long total = totalRequests.get();
    return total > 0 ? (double) totalResponseTime.get() / total : 0.0;
  }

  public long getPercentileResponseTime(double percentile) {
    if (responseTimes.isEmpty()) return 0;

    List<Long> sorted = new ArrayList<>(responseTimes);
    sorted.sort(null);

    int index = (int) Math.ceil(sorted.size() * percentile / 100.0) - 1;
    return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
  }

  public long getP95ResponseTime() {
    return getPercentileResponseTime(95.0);
  }

  public long getP99ResponseTime() {
    return getPercentileResponseTime(99.0);
  }

  public double getCurrentThroughput() {
    Duration testDuration = Duration.between(testStartTime, Instant.now());
    long seconds = Math.max(1, testDuration.getSeconds());
    return (double) totalRequests.get() / seconds;
  }

  public Map<Integer, Long> getStatusCodeDistribution() {
    return statusCodeCounts.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get()));
  }

  // === Windowed Reporting - FIXED ===

  private void startWindowedReporting() {
    scheduler.scheduleAtFixedRate(
        this::createNewWindow, WINDOW_SIZE_SECONDS, WINDOW_SIZE_SECONDS, TimeUnit.SECONDS);
  }

  private synchronized void createNewWindow() {
    Instant now = Instant.now();

    // Finalize current window
    if (currentWindow != null) {
      currentWindow.finalize(now);

      // Always add the window to track progression, even if empty
      metricsWindows.offer(currentWindow);

      // Log window summary (only if it has data)
      if (currentWindow.getRequestCount() > 0) {
        logWindowSummary(currentWindow);
      } else {
        log.debug("Window {} had no requests", currentWindow.getWindowNumber());
      }
    }

    // Create new window
    currentWindow = new MetricsWindow(now, WINDOW_SIZE_SECONDS);

    // Trim old windows (keep last 24 windows = 2 minutes)
    while (metricsWindows.size() > 24) {
      metricsWindows.poll();
    }
  }

  private void logWindowSummary(MetricsWindow window) {
    StringBuilder summary = new StringBuilder();
    summary.append(
        String.format(
            "=== WINDOW %d (%s - %s) ===\n",
            window.getWindowNumber(),
            window.getStartTime().toString().substring(11, 19), // HH:mm:ss format
            window.getEndTime() != null
                ? window.getEndTime().toString().substring(11, 19)
                : "ongoing"));

    summary.append(
        String.format(
            "Requests: %d (Success: %d, Failed: %d)\n",
            window.getRequestCount(), window.getSuccessfulRequests(), window.getFailedRequests()));

    if (window.getRequestCount() > 0) {
      summary.append(
          String.format(
              "Response Times: Avg=%.1fms, P50=%dms, P95=%dms, P99=%dms (Min=%dms, Max=%dms)\n",
              window.getAverageResponseTime(),
              window.getP50ResponseTime(),
              window.getP95ResponseTime(),
              window.getP99ResponseTime(),
              window.getMinResponseTime(),
              window.getMaxResponseTime()));

      summary.append(
          String.format(
              "Error Rate: %.2f%%, Throughput: %.2f req/sec\n",
              window.getErrorRate(), window.getThroughput()));
    }

    // CLOSED model specific info
    if (workloadModel == TestPlanSpec.WorkLoadModel.CLOSED) {
      summary.append(
          String.format(
              "Users: Active=%d, Started=%d, Completed=%d\n",
              activeUsers.get(),
              window.getUsersStarted().size(),
              window.getUsersCompleted().size()));
    }

    if (window.getBackPressureEvents() > 0) {
      summary.append(String.format("Back-pressure Events: %d\n", window.getBackPressureEvents()));
    }

    if (window.getSlaViolationCount() > 0) {
      summary.append(String.format("SLA Violations: %d\n", window.getSlaViolationCount()));
    }

    if (window.getErrorCount() > 0) {
      summary.append(String.format("Errors: %d\n", window.getErrorCount()));
    }

    log.info("\n{}", summary.toString());
  }

  // === Comprehensive Report Generation ===

  public ComprehensiveTestReport generateComprehensiveReport(
      Instant testEndTime, String terminationReason) {
    Duration totalDuration = Duration.between(testStartTime, testEndTime);

    // Convert windows to simplified summaries - INCLUDE ALL WINDOWS
    List<WindowSummary> windowSummaries =
        metricsWindows.stream()
            .map(
                window ->
                    WindowSummary.builder()
                        .windowNumber(window.getWindowNumber())
                        .startTime(window.getStartTime())
                        .endTime(window.getEndTime())
                        .windowSizeSeconds(window.getWindowSizeSeconds())
                        .requestCount(window.getRequestCount())
                        .successfulRequests(window.getSuccessfulRequests())
                        .failedRequests(window.getFailedRequests())
                        .errorRate(window.getErrorRate())
                        .averageResponseTime(window.getAverageResponseTime())
                        .p50ResponseTime(window.getP50ResponseTime())
                        .p95ResponseTime(window.getP95ResponseTime())
                        .p99ResponseTime(window.getP99ResponseTime())
                        .minResponseTime(window.getMinResponseTime())
                        .maxResponseTime(window.getMaxResponseTime())
                        .throughput(window.getThroughput())
                        .usersStarted(new HashSet<>(window.getUsersStarted()))
                        .usersCompleted(new HashSet<>(window.getUsersCompleted()))
                        .backPressureEvents(window.getBackPressureEvents())
                        .slaViolationCount(window.getSlaViolationCount())
                        .errorCount(window.getErrorCount())
                        .build())
            .collect(Collectors.toList());

    return ComprehensiveTestReport.builder()
        .testStartTime(testStartTime)
        .testEndTime(testEndTime)
        .totalDuration(totalDuration)
        .terminationReason(terminationReason)
        .finalPhase(currentPhase.get())

        // Request metrics
        .totalRequests(totalRequests.get())
        .successfulRequests(successfulRequests.get())
        .failedRequests(failedRequests.get())
        .errorRate(getErrorRate())

        // Response time metrics
        .averageResponseTime(getAverageResponseTime())
        .p50ResponseTime(getPercentileResponseTime(50.0))
        .p95ResponseTime(getP95ResponseTime())
        .p99ResponseTime(getPercentileResponseTime(99.0))
        .minResponseTime(responseTimes.stream().mapToLong(Long::longValue).min().orElse(0))
        .maxResponseTime(responseTimes.stream().mapToLong(Long::longValue).max().orElse(0))

        // Throughput
        .averageThroughput(getCurrentThroughput())
        .peakThroughput(calculatePeakThroughput())

        // Status codes
        .statusCodeDistribution(getStatusCodeDistribution())

        // Operational metrics
        .backPressureEvents(backPressureEvents.get())
        .scheduledRequests(scheduledRequests.get())

        // Error analysis
        .errorSummary(generateErrorSummary())
        .topErrors(getTopErrors(10))

        // SLA violations
        .slaViolationSummary(generateSLAViolationSummary())
        .allSlaViolations(new ArrayList<>(slaViolations))

        // User metrics (CLOSED model)
        .userMetricsSummary(generateUserMetricsSummary())

        // Simplified window analysis
        .windowSummaries(windowSummaries)
        .windowAnalysis(generateWindowAnalysis())
        .build();
  }

  private double calculatePeakThroughput() {
    return metricsWindows.stream()
        .filter(window -> window.getRequestCount() > 0)
        .mapToDouble(MetricsWindow::getThroughput)
        .max()
        .orElse(0.0);
  }

  private ErrorSummary generateErrorSummary() {
    Map<String, Long> errorsByType =
        errors.stream()
            .collect(Collectors.groupingBy(ErrorInfo::getExceptionType, Collectors.counting()));

    Map<String, Long> errorsByMessage =
        errors.stream()
            .collect(Collectors.groupingBy(ErrorInfo::getMessage, Collectors.counting()));

    return ErrorSummary.builder()
        .totalErrors(errors.size())
        .errorsByType(errorsByType)
        .errorsByMessage(errorsByMessage)
        .errorsByPhase(
            errors.stream()
                .collect(Collectors.groupingBy(error -> "Unknown", Collectors.counting())))
        .build();
  }

  private List<ErrorInfo> getTopErrors(int limit) {
    return errors.stream()
        .sorted(Comparator.comparing(ErrorInfo::getTimestamp).reversed())
        .limit(limit)
        .collect(Collectors.toList());
  }

  private SLAViolationSummary generateSLAViolationSummary() {
    Map<String, Long> violationsByType =
        slaViolations.stream()
            .collect(
                Collectors.groupingBy(SLAViolationInfo::getViolationType, Collectors.counting()));

    return SLAViolationSummary.builder()
        .totalViolations(slaViolations.size())
        .violationsByType(violationsByType)
        .firstViolation(slaViolations.stream().findFirst().orElse(null))
        .lastViolation(slaViolations.stream().reduce((first, second) -> second).orElse(null))
        .build();
  }

  private UserMetricsSummary generateUserMetricsSummary() {
    if (workloadModel != TestPlanSpec.WorkLoadModel.CLOSED) {
      return null;
    }

    List<UserPerformance> userPerformances =
        userMetrics.entrySet().stream()
            .map(
                entry -> {
                  int userId = entry.getKey();
                  UserMetrics metrics = entry.getValue();

                  return UserPerformance.builder()
                      .userId(userId)
                      .requestCount(metrics.getRequestCount())
                      .errorCount(metrics.getErrorCount())
                      .averageResponseTime(metrics.getAverageResponseTime())
                      .startTime(metrics.getStartTime())
                      .endTime(metrics.getEndTime())
                      .duration(metrics.getDuration())
                      .build();
                })
            .sorted(Comparator.comparing(UserPerformance::getUserId))
            .collect(Collectors.toList());

    return UserMetricsSummary.builder()
        .totalUsers(userMetrics.size())
        .maxConcurrentUsers(activeUsers.get())
        .userPerformances(userPerformances)
        .build();
  }

  private WindowAnalysis generateWindowAnalysis() {
    var dataWindows =
        metricsWindows.stream()
            .filter(window -> window.getRequestCount() > 0)
            .collect(Collectors.toList());

    if (dataWindows.isEmpty()) {
      return null;
    }

    DoubleSummaryStatistics throughputStats =
        dataWindows.stream().mapToDouble(MetricsWindow::getThroughput).summaryStatistics();

    DoubleSummaryStatistics responseTimeStats =
        dataWindows.stream().mapToDouble(MetricsWindow::getAverageResponseTime).summaryStatistics();

    return WindowAnalysis.builder()
        .totalWindows(dataWindows.size())
        .windowSize(WINDOW_SIZE_SECONDS)
        .throughputStats(throughputStats)
        .responseTimeStats(responseTimeStats)
        .peakWindow(
            dataWindows.stream()
                .max(Comparator.comparing(MetricsWindow::getThroughput))
                .orElse(null))
        .build();
  }

  public void shutdown() {
    // Finalize current window
    createNewWindow();
    log.info("TestMetrics shutdown completed");
  }
}

// === Supporting Classes - FIXED ===
