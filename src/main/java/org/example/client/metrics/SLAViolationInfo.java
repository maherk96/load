package org.example.client.metrics;

import java.time.Instant;
import lombok.Builder;
import lombok.Data;
import org.example.client.enums.TestPhase;

@Data
@Builder
public class SLAViolationInfo {
  private final Instant timestamp;
  private final String violationType;
  private final double actualValue;
  private final double threshold;
  private final String action;
  private final TestPhase testPhase;
}
