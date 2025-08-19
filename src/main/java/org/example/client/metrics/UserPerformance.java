package org.example.client.metrics;

import java.time.Duration;
import java.time.Instant;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
class UserPerformance {
  private final int userId;
  private final long requestCount;
  private final long errorCount;
  private final double averageResponseTime;
  private final Instant startTime;
  private final Instant endTime;
  private final Duration duration;
}
