package org.example.client;

import java.util.concurrent.atomic.AtomicBoolean;

public class SLAEvaluator {

  private final String executionId;
  private AtomicBoolean isViolated = new AtomicBoolean(false);

  public SLAEvaluator(String executionId) {
    this.executionId = executionId;
  }
}
