package org.example.client.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;
import org.example.client.enums.TestPhase;

@Data
@Builder
public class ComprehensiveTestReport {
  // Test lifecycle
  private final Instant testStartTime;
  private final Instant testEndTime;
  private final Duration totalDuration;
  private final String terminationReason;
  private final TestPhase finalPhase;

  // Request metrics
  private final long totalRequests;
  private final long successfulRequests;
  private final long failedRequests;
  private final double errorRate;

  // Response time metrics
  private final double averageResponseTime;
  private final long p50ResponseTime;
  private final long p95ResponseTime;
  private final long p99ResponseTime;
  private final long minResponseTime;
  private final long maxResponseTime;

  // Throughput
  private final double averageThroughput;
  private final double peakThroughput;

  // Status codes
  private final Map<Integer, Long> statusCodeDistribution;

  // Operational metrics
  private final long backPressureEvents;
  private final long scheduledRequests;

  // Error analysis
  private final ErrorSummary errorSummary;
  private final List<ErrorInfo> topErrors;

  // SLA violations
  private final SLAViolationSummary slaViolationSummary;
  private final List<SLAViolationInfo> allSlaViolations;

  // User metrics (CLOSED model only)
  private final UserMetricsSummary userMetricsSummary;

  // Simplified window analysis
  private final List<WindowSummary> windowSummaries;
  private final WindowAnalysis windowAnalysis;
}
