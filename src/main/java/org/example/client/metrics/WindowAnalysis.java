package org.example.client.metrics;

import java.util.DoubleSummaryStatistics;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WindowAnalysis {
  private final int totalWindows;
  private final int windowSize;
  private final DoubleSummaryStatistics throughputStats;
  private final DoubleSummaryStatistics responseTimeStats;
  private final MetricsWindow peakWindow;
}
