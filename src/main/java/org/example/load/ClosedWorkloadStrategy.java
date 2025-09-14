// Main orchestrator class - with cancellation mechanism
package org.example.load;

import org.example.dto.TestPlanSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClosedWorkloadStrategy implements WorkloadStrategy {

  private static final Logger log = LoggerFactory.getLogger(ClosedWorkloadStrategy.class);

  private final TestPhaseManager phaseManager;
  private final RequestExecutor requestExecutor;
  private final ResourceManager resourceManager;
  private final TimingUtils timingUtils;
  private final AtomicBoolean cancelled;

  private CountDownLatch userCompletionLatch;
  private ScheduledFuture<?> warmupTask;
  private ScheduledFuture<?> phaseTransitionTask;
  private ScheduledFuture<?> holdTimeTask;

  public ClosedWorkloadStrategy(TestPhaseManager phaseManager, RequestExecutor requestExecutor,
                                ResourceManager resourceManager, AtomicBoolean cancelled) {
    this.phaseManager = phaseManager;
    this.requestExecutor = requestExecutor;
    this.resourceManager = resourceManager;
    this.timingUtils = new TimingUtils();
    this.cancelled = cancelled;
  }

  @Override
  public void execute(TestPlanSpec testPlanSpec) {
    try {
      var loadModel = testPlanSpec.getExecution().getLoadModel();

      var warmupDuration = timingUtils.parseDuration(loadModel.getWarmup());
      var rampUpDuration = timingUtils.parseDuration(loadModel.getRampUp());
      var holdDuration = timingUtils.parseDuration(loadModel.getHoldFor());

      int totalUsers = loadModel.getUsers();
      int iterationsPerUser = loadModel.getIterations();

      userCompletionLatch = new CountDownLatch(totalUsers);

      log.info("Executing CLOSED workload: {} users, {} iterations per user, ramp-up: {}s, hold: {}s",
              totalUsers, iterationsPerUser, rampUpDuration.getSeconds(), holdDuration.getSeconds());

      // Phase 1: Warmup
      if (warmupDuration.toMillis() > 0 && !cancelled.get()) {
        executeWarmup(testPlanSpec, warmupDuration);
      }

      if (!phaseManager.isTestRunning() || cancelled.get()) return;

      // Phase 2: Ramp-up and Hold
      executeRampUpAndHold(testPlanSpec, rampUpDuration, holdDuration, totalUsers, iterationsPerUser);

      // Wait for completion
      waitForUserCompletion();
    } finally {
      cancelScheduledTasks();
    }
  }

  private void executeWarmup(TestPlanSpec testPlanSpec, Duration warmupDuration) {
    phaseManager.setPhase(TestPhaseManager.TestPhase.WARMUP);
    log.info("Starting warmup phase for {} seconds", warmupDuration.getSeconds());

    var warmupEnd = Instant.now().plus(warmupDuration);
    warmupTask = resourceManager.getSchedulerService().scheduleWithFixedDelay(() -> {
      if (!phaseManager.isTestRunning() || cancelled.get() || Instant.now().isAfter(warmupEnd)) {
        return;
      }
      try {
        requestExecutor.executeAllRequests(testPlanSpec, phaseManager, -1, true, null, cancelled);
      } catch (Exception e) {
        log.debug("Warmup request failed: {}", e.getMessage());
      }
    }, 0, 1000, TimeUnit.MILLISECONDS);

    timingUtils.sleep(warmupDuration, cancelled);

    if (warmupTask != null) {
      warmupTask.cancel(false);
    }
    log.info("Warmup phase completed");
  }

  private void executeRampUpAndHold(TestPlanSpec testPlanSpec, Duration rampUpDuration,
                                    Duration holdDuration, int totalUsers, int iterationsPerUser) {
    if (cancelled.get()) return;

    phaseManager.setPhase(TestPhaseManager.TestPhase.RAMP_UP);

    var rampUpStart = Instant.now();
    var holdStart = rampUpStart.plus(rampUpDuration);
    var testEndTime = holdStart.plus(holdDuration);

    // Schedule phase transition and termination
    schedulePhaseTransition(rampUpDuration);
    scheduleHoldTimeTermination(testEndTime);

    // Start user threads
    for (int userId = 0; userId < totalUsers; userId++) {
      if (cancelled.get()) break;

      long userStartDelay = (userId * rampUpDuration.toMillis()) / totalUsers;
      startUserThread(testPlanSpec, userId, userStartDelay, iterationsPerUser, testEndTime);
    }
  }

  private void startUserThread(TestPlanSpec testPlanSpec, int userId, long startDelay,
                               int iterations, Instant testEndTime) {
    resourceManager.getMainExecutor().submit(() -> {
      executeUserThread(testPlanSpec, userId, startDelay, iterations, testEndTime);
    });
  }

  private void executeUserThread(TestPlanSpec testPlanSpec, int userId, long startDelay,
                                 int iterations, Instant testEndTime) {
    try {
      if (startDelay > 0 && !cancelled.get()) {
        Thread.sleep(startDelay);
      }

      if (cancelled.get()) return;

      log.debug("User {} started with {} iterations (delay: {}ms)", userId, iterations, startDelay);

      int completedIterations = 0;
      for (int i = 0; i < iterations && phaseManager.isTestRunning() && !cancelled.get(); i++) {
        if (Instant.now().isAfter(testEndTime)) {
          log.debug("User {} stopping due to hold time expiration after {} iterations",
                  userId, completedIterations);
          break;
        }

        requestExecutor.executeAllRequests(testPlanSpec, phaseManager, userId, false, null, cancelled);
        completedIterations++;

        if (i < iterations - 1 && !cancelled.get()) {
          timingUtils.applyThinkTime(testPlanSpec.getExecution().getThinkTime(), cancelled);
        }
      }

      log.debug("User {} completed {} out of {} iterations", userId, completedIterations, iterations);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("User {} interrupted", userId);
    } finally {
      userCompletionLatch.countDown();
      log.debug("User {} finished", userId);
    }
  }

  private void schedulePhaseTransition(Duration rampUpDuration) {
    phaseTransitionTask = resourceManager.getSchedulerService().schedule(() -> {
      if (phaseManager.isTestRunning() && !cancelled.get()) {
        phaseManager.setPhase(TestPhaseManager.TestPhase.HOLD);
      }
    }, rampUpDuration.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void scheduleHoldTimeTermination(Instant testEndTime) {
    long delayMs = Duration.between(Instant.now(), testEndTime).toMillis();
    holdTimeTask = resourceManager.getSchedulerService().schedule(() -> {
      if (phaseManager.isTestRunning() && !cancelled.get()) {
        phaseManager.terminateTest("HOLD_TIME_EXPIRED");
      }
    }, delayMs, TimeUnit.MILLISECONDS);
  }

  private void waitForUserCompletion() {
    try {
      userCompletionLatch.await();
      if (phaseManager.isTestRunning() && !cancelled.get()) {
        phaseManager.terminateTest("ALL_ITERATIONS_COMPLETED");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (phaseManager.isTestRunning()) {
        log.warn("User completion wait interrupted");
      }
    }
  }

  private void cancelScheduledTasks() {
    if (warmupTask != null) {
      warmupTask.cancel(false);
    }
    if (phaseTransitionTask != null) {
      phaseTransitionTask.cancel(false);
    }
    if (holdTimeTask != null) {
      holdTimeTask.cancel(false);
    }
  }
}

// ===== TIMING UTILITIES =====

