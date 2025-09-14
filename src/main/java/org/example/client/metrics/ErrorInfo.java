package org.example.client.metrics;

import java.time.Instant;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ErrorInfo {
  private final Instant timestamp;
  private final String message;
  private final String exceptionType;
  private final int userId;
}
