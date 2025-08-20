package org.example.client.metrics;

import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ErrorSummary {
  private final long totalErrors;
  private final Map<String, Long> errorsByType;
  private final Map<String, Long> errorsByMessage;
  private final Map<String, Long> errorsByPhase;
}
