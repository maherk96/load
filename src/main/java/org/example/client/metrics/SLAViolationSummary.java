package org.example.client.metrics;

import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SLAViolationSummary {
  private final long totalViolations;
  private final Map<String, Long> violationsByType;
  private final SLAViolationInfo firstViolation;
  private final SLAViolationInfo lastViolation;
}
