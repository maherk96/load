package org.example.client.metrics;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserMetricsSummary {
  private final int totalUsers;
  private final int maxConcurrentUsers;
  private final List<UserPerformance> userPerformances;
}
