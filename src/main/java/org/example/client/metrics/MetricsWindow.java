package org.example.client.metrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Data;

@Data
public class MetricsWindow {
  private static final AtomicInteger windowCounter = new AtomicInteger(0);

  private final int windowNumber;
  private final Instant startTime;
  private final int windowSizeSeconds;
  private volatile Instant endTime;

  // Request metrics
  private final AtomicLong requestCount = new AtomicLong(0);
  private final AtomicLong successfulRequests = new AtomicLong(0);
  private final AtomicLong failedRequests = new AtomicLong(0);
  private final AtomicLong totalResponseTime = new AtomicLong(0);

  // Response time tracking
  private volatile long minResponseTime = Long.MAX_VALUE;
  private volatile long maxResponseTime = Long.MIN_VALUE;
  private final Queue<Long> responseTimes = new ConcurrentLinkedQueue<>();

  // Error tracking
  private final AtomicLong errorCount = new AtomicLong(0);

  // SLA violations
  private final AtomicLong slaViolationCount = new AtomicLong(0);

  // User tracking (CLOSED model)
  private final Set<Integer> usersStarted = ConcurrentHashMap.newKeySet();
  private final Set<Integer> usersCompleted = ConcurrentHashMap.newKeySet();

  // Operational events
  private final AtomicLong backPressureEvents = new AtomicLong(0);

  public MetricsWindow(Instant startTime, int windowSizeSeconds) {
    this.windowNumber = windowCounter.incrementAndGet();
    this.startTime = startTime;
    this.windowSizeSeconds = windowSizeSeconds;
  }

  // FIXED: Simplified recording - no timestamp checking
  public void recordResponse(int statusCode, long responseTime, Instant timestamp) {
    requestCount.incrementAndGet();
    totalResponseTime.addAndGet(responseTime);

    // Update min/max
    updateMinMax(responseTime);

    // Store for percentile calculation (limit size)
    responseTimes.offer(responseTime);
    while (responseTimes.size() > 1000) {
      responseTimes.poll();
    }

    if (statusCode < 400) {
      successfulRequests.incrementAndGet();
    } else {
      failedRequests.incrementAndGet();
    }
  }

  private synchronized void updateMinMax(long responseTime) {
    if (responseTime < minResponseTime) {
      minResponseTime = responseTime;
    }
    if (responseTime > maxResponseTime) {
      maxResponseTime = responseTime;
    }
  }

  public void recordError(ErrorInfo error) {
    errorCount.incrementAndGet();
  }

  public void recordSLAViolation(SLAViolationInfo violation) {
    slaViolationCount.incrementAndGet();
  }

  public void recordUserStarted(int userId) {
    usersStarted.add(userId);
  }

  public void recordUserCompleted(int userId) {
    usersCompleted.add(userId);
  }

  public void recordBackPressureEvent(Instant timestamp) {
    backPressureEvents.incrementAndGet();
  }

  public void finalize(Instant endTime) {
    this.endTime = endTime;
  }

  // Calculated metrics
  public double getErrorRate() {
    long total = requestCount.get();
    return total > 0 ? (double) failedRequests.get() / total * 100.0 : 0.0;
  }

  public double getAverageResponseTime() {
    long total = requestCount.get();
    return total > 0 ? (double) totalResponseTime.get() / total : 0.0;
  }

  public long getMinResponseTime() {
    return minResponseTime == Long.MAX_VALUE ? 0 : minResponseTime;
  }

  public long getMaxResponseTime() {
    return maxResponseTime == Long.MIN_VALUE ? 0 : maxResponseTime;
  }

  public long getP50ResponseTime() {
    return getPercentileResponseTime(50.0);
  }

  public long getP95ResponseTime() {
    return getPercentileResponseTime(95.0);
  }

  public long getP99ResponseTime() {
    return getPercentileResponseTime(99.0);
  }

  private long getPercentileResponseTime(double percentile) {
    if (responseTimes.isEmpty()) return 0;

    List<Long> sorted = new ArrayList<>(responseTimes);
    sorted.sort(null);

    int index = (int) Math.ceil(sorted.size() * percentile / 100.0) - 1;
    return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
  }

  public double getThroughput() {
    return windowSizeSeconds > 0 ? (double) requestCount.get() / windowSizeSeconds : 0.0;
  }

  // Getters
  public long getRequestCount() {
    return requestCount.get();
  }

  public long getSuccessfulRequests() {
    return successfulRequests.get();
  }

  public long getFailedRequests() {
    return failedRequests.get();
  }

  public long getErrorCount() {
    return errorCount.get();
  }

  public long getSlaViolationCount() {
    return slaViolationCount.get();
  }

  public long getBackPressureEvents() {
    return backPressureEvents.get();
  }
}
