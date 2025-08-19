package org.example.client.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Data;

@Data
public class UserMetrics {
  private final int userId;
  private Instant startTime;
  private Instant endTime;
  private final AtomicLong requestCount = new AtomicLong(0);
  private final AtomicLong errorCount = new AtomicLong(0);
  private final AtomicLong totalResponseTime = new AtomicLong(0);
  private final Queue<ErrorInfo> errors = new ConcurrentLinkedQueue<>();

  public UserMetrics(int userId) {
    this.userId = userId;
  }

  public void recordResponse(int statusCode, long responseTime, Instant timestamp) {
    requestCount.incrementAndGet();
    totalResponseTime.addAndGet(responseTime);

    if (statusCode >= 400) {
      errorCount.incrementAndGet();
    }
  }

  public void recordError(ErrorInfo error) {
    errorCount.incrementAndGet();
    errors.offer(error);
  }

  public double getAverageResponseTime() {
    long total = requestCount.get();
    return total > 0 ? (double) totalResponseTime.get() / total : 0.0;
  }

  public Duration getDuration() {
    if (startTime != null && endTime != null) {
      return Duration.between(startTime, endTime);
    }
    return Duration.ZERO;
  }

  // Getters for AtomicLong values
  public long getRequestCount() {
    return requestCount.get();
  }

  public long getErrorCount() {
    return errorCount.get();
  }
}
