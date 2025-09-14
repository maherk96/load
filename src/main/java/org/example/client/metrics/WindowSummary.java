package org.example.client.metrics;

import java.time.Instant;
import java.util.Set;
import lombok.Builder;
import lombok.Data;

// Simplified window summary without response times array
@Data
@Builder
public class WindowSummary {
  private final int windowNumber;
  private final Instant startTime;
  private final Instant endTime;
  private final int windowSizeSeconds;

  // Request metrics
  private final long requestCount;
  private final long successfulRequests;
  private final long failedRequests;
  private final double errorRate;

  // Response time metrics (no individual times)
  private final double averageResponseTime;
  private final long p50ResponseTime;
  private final long p95ResponseTime;
  private final long p99ResponseTime;
  private final long minResponseTime;
  private final long maxResponseTime;

  // Throughput
  private final double throughput;

  // User metrics (CLOSED model)
  private final Set<Integer> usersStarted;
  private final Set<Integer> usersCompleted;

  // Operational
  private final long backPressureEvents;
  private final long slaViolationCount;
  private final long errorCount;
}
