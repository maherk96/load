// Main orchestrator class - with cancellation mechanism
package org.example.load;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.example.dto.TestPlanSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simplified Load Test Execution Runner that orchestrates load tests with cancellation support.
 *
 * This refactored version focuses purely on coordination and delegates
 * specific responsibilities to specialized components.
 */
public class LoadTestExecutionRunner {

  // ENHANCED: Better user thread execution with more detailed logging and proper termination checks
private void executeUserThread(int userId, long startDelay, int iterations, Instant testEndTime) {
    try {
        // Wait for ramp-up delay
        if (startDelay > 0) {
            Thread.sleep(startDelay);
        }

        // Check if test was terminated during ramp-up delay
        if (!testRunning.get()) {
            log.debug("User {} terminating before start - test already stopped", userId);
            return;
        }

        metrics.userStarted(userId);
        log.debug("User {} started with {} iterations (delay: {}ms)", userId, iterations, startDelay);

        int completedIterations = 0;
        for (int i = 0; i < iterations; i++) {
            // FIXED: Check termination conditions at the start of EVERY iteration
            if (!testRunning.get()) {
                log.debug("User {} stopping due to test termination after {} iterations", 
                    userId, completedIterations);
                break;
            }

            // FIXED: Check hold time expiration before starting request
            if (Instant.now().isAfter(testEndTime)) {
                log.debug("User {} stopping due to hold time expiration after {} iterations",
                    userId, completedIterations);
                break;
            }

            // FIXED: Pass testRunning flag to executeRequest for early termination
            boolean requestCompleted = executeRequestWithTerminationCheck(userId, false);
            if (!requestCompleted) {
                log.debug("User {} request was terminated, stopping execution", userId);
                break;
            }
            
            completedIterations++;

            // Apply think time between requests (except for last iteration)
            if (i < iterations - 1) {
                // FIXED: Check termination before and during think time
                if (!applyThinkTimeWithTerminationCheck()) {
                    log.debug("User {} interrupted during think time", userId);
                    break;
                }
            }
        }

        log.debug("User {} completed {} out of {} iterations", userId, completedIterations, iterations);

    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.debug("User {} interrupted", userId);
    } finally {
        metrics.userCompleted(userId);
        log.debug("User {} finished", userId);
    }
}

// FIXED: Enhanced executeRequest that can be terminated early
private boolean executeRequestWithTerminationCheck(int userId, boolean isWarmup) {
    try {
        // Check termination before starting request
        if (!testRunning.get()) {
            return false;
        }

        metrics.incrementActiveRequests();

        // Get first scenario and request (simplified for example)
        var scenario = testPlanSpec.getTestSpec().getScenarios().get(0);
        var request = scenario.getRequests();

        Instant requestStart = Instant.now();
        
        // FIXED: Execute request with timeout/interruption awareness
        RestResponseData response = httpClient.execute(request);
        
        // Check if test was terminated during request execution
        if (!testRunning.get()) {
            log.debug("Test terminated during request execution for user {}", userId);
            return false;
        }
        
        Instant requestEnd = Instant.now();
        long responseTime = Duration.between(requestStart, requestEnd).toMillis();

        if (!isWarmup) {
            // Record metrics with user tracking
            if (userId >= 0) {
                metrics.recordResponse(response.getStatusCode(), responseTime, userId);
            } else {
                metrics.recordResponse(response.getStatusCode(), responseTime);
            }
        }

        log.debug("Request completed: {} ms, status: {}, user: {}", 
            responseTime, response.getStatusCode(), userId);
        
        return true;

    } catch (Exception e) {
        if (!isWarmup) {
            if (userId >= 0) {
                metrics.recordError(e.getMessage(), e, userId);
            } else {
                metrics.recordError(e.getMessage(), e);
            }
        }
        log.debug("Request failed: {}, user: {}", e.getMessage(), userId);
        return false; // Consider failed requests as reason to stop
    } finally {
        metrics.decrementActiveRequests();
    }
}

// FIXED: Think time with proper termination checking
private boolean applyThinkTimeWithTerminationCheck() {
    var thinkTime = testPlanSpec.getExecution().getThinkTime();
    if (thinkTime == null) return true;

    try {
        int delay;
        if (thinkTime.getType() == TestPlanSpec.ThinkTimeType.FIXED) {
            delay = thinkTime.getMin();
        } else {
            // RANDOM think time between min and max
            Random random = new Random();
            delay = thinkTime.getMin() + random.nextInt(thinkTime.getMax() - thinkTime.getMin() + 1);
        }

        if (delay > 0) {
            log.trace("Applying think time: {}ms", delay);
            
            // FIXED: Break down long think times into smaller chunks for better responsiveness
            int chunkSize = Math.min(delay, 100); // Check termination every 100ms max
            int remainingDelay = delay;
            
            while (remainingDelay > 0 && testRunning.get()) {
                int currentChunk = Math.min(remainingDelay, chunkSize);
                Thread.sleep(currentChunk);
                remainingDelay -= currentChunk;
            }
            
            // Return false if test was terminated during think time
            return testRunning.get();
        }
        
        return true;
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
    }
}

// ORIGINAL executeRequest method - keep this for backward compatibility with open workload
private void executeRequest(Semaphore concurrencyLimiter, int userId, boolean isWarmup) {
    executeRequestWithTerminationCheck(userId, isWarmup);
    
    if (concurrencyLimiter != null) {
        concurrencyLimiter.release();
    }
}

  private static final Logger log = LoggerFactory.getLogger(LoadTestExecutionRunner.class);

  private final TestPlanSpec testPlanSpec;
  private final TestPhaseManager phaseManager;
  private final RequestExecutor requestExecutor;
  private final ResourceManager resourceManager;
  private final WorkloadStrategyFactory strategyFactory;

  private final AtomicReference<String> terminationReason = new AtomicReference<>();
  private final AtomicBoolean cancelled = new AtomicBoolean(false);

  /**
   * Constructs a new LoadTestExecutionRunner with the specified test plan.
   */
  public LoadTestExecutionRunner(TestPlanSpec testPlanSpec) {
    this.testPlanSpec = testPlanSpec;
    this.phaseManager = new TestPhaseManager();
    this.requestExecutor = new RequestExecutor(testPlanSpec);
    this.resourceManager = new ResourceManager();
    this.strategyFactory = new WorkloadStrategyFactory();
  }

  /**
   * Main execution method that orchestrates the entire load test.
   */
  public CompletableFuture<Void> execute() {
    return CompletableFuture.runAsync(() -> {
      try {
        log.info("Starting load test execution: {}", testPlanSpec.getTestSpec().getId());

        phaseManager.startTest();
        var testStartTime = Instant.now();

        // Create and execute workload strategy
        var strategy = strategyFactory.createStrategy(
                testPlanSpec.getExecution().getLoadModel(),
                phaseManager,
                requestExecutor,
                resourceManager,
                cancelled
        );

        strategy.execute(testPlanSpec);

        // Wait for completion
        phaseManager.waitForCompletion();

        var testEndTime = Instant.now();
        var totalDuration = Duration.between(testStartTime, testEndTime);

        phaseManager.completeTest();
        log.info("Load test completed. Duration: {}s, Reason: {}",
                totalDuration.getSeconds(), getTerminationReason());

      } catch (Exception e) {
        log.error("Load test execution failed", e);
        phaseManager.terminateTest("EXECUTION_ERROR: " + e.getMessage());
        throw new RuntimeException("Load test execution failed", e);
      } finally {
        cleanup();
      }
    }, resourceManager.getMainExecutor());
  }

  /**
   * Cancels the load test execution gracefully.
   * This will stop further request submissions and let running requests complete.
   */
  public void cancel() {
    if (cancelled.compareAndSet(false, true)) {
      log.info("Load test cancellation requested - stopping execution gracefully");
      terminateTest("CANCELLED");
    } else {
      log.debug("Load test cancellation already in progress");
    }
  }

  /**
   * Returns true if the test has been cancelled.
   */
  public boolean isCancelled() {
    return cancelled.get();
  }

  /**
   * Terminates the test with the specified reason.
   */
  public void terminateTest(String reason) {
    terminationReason.compareAndSet(null, reason);
    phaseManager.terminateTest(reason);
  }

  public String getTerminationReason() {
    return terminationReason.get();
  }

  /**
   * Cleanup all resources.
   */
  public void cleanup() {
    log.info("Starting load test cleanup...");
    try {
      phaseManager.cleanup();
      requestExecutor.cleanup();
      resourceManager.cleanup();
      log.info("Load test cleanup completed successfully");
    } catch (Exception e) {
      log.error("Unexpected error during cleanup", e);
    }
  }
}

// ===== PHASE MANAGEMENT =====

/**
 * Manages test phases and state transitions.
 */

// ===== REQUEST EXECUTION =====

/**
 * Handles HTTP request execution and logging.
 */

// ===== RESOURCE MANAGEMENT =====

/**
 * Manages executors, rate limiters, and other shared resources.
 */

// ===== WORKLOAD STRATEGIES =====

/**
 * Factory for creating workload strategies.
 */

/**
 * Base interface for workload execution strategies.
 */

/**
 * Strategy for executing CLOSED workload model with cancellation support.
 */
/**
 * Strategy for executing OPEN workload model with cancellation support.
 */


// ===== TIMING UTILITIES =====

/**
 * Utility class for timing and duration operations with cancellation support.
 */
