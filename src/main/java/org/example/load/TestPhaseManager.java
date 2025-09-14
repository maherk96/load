package org.example.load;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class TestPhaseManager {

  private static final Logger log = LoggerFactory.getLogger(TestPhaseManager.class);

  public enum TestPhase {
    INITIALIZING, WARMUP, RAMP_UP, HOLD, COMPLETED, TERMINATED
  }

  private final AtomicReference<TestPhase> currentPhase = new AtomicReference<>(TestPhase.INITIALIZING);
  private final AtomicBoolean testRunning = new AtomicBoolean(false);
  private final CountDownLatch testCompletionLatch = new CountDownLatch(1);

  public void startTest() {
    testRunning.set(true);
    currentPhase.set(TestPhase.INITIALIZING);
  }

  public void setPhase(TestPhase phase) {
    currentPhase.set(phase);
    log.debug("Phase transition: {}", phase);
  }

  public TestPhase getCurrentPhase() {
    return currentPhase.get();
  }

  public boolean isTestRunning() {
    return testRunning.get();
  }

  public void terminateTest(String reason) {
    if (testRunning.compareAndSet(true, false)) {
      currentPhase.set(TestPhase.TERMINATED);
      testCompletionLatch.countDown();
      log.info("Test termination initiated: {}", reason);
    }
  }

  public void completeTest() {
    if (testRunning.compareAndSet(true, false)) {
      currentPhase.set(TestPhase.COMPLETED);
      testCompletionLatch.countDown();
    }
  }

  public void waitForCompletion() {
    try {
      testCompletionLatch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public void cleanup() {
    testRunning.set(false);
  }
}
