package org.example.load;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.RateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.example.client.LoadHttpClient;
import org.example.client.response.RestResponseData;
import org.example.dto.TestPlanSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enhanced Load Test Execution Runner that orchestrates and executes load tests for RESTful APIs.
 *
 * <p>This refactored version focuses purely on test execution and logging without metrics collection
 * or SLA monitoring. Key features include:
 *
 * <ul>
 *   <li>Virtual threads (Java 21) for lightweight concurrency
 *   <li>Support for multiple requests per scenario
 *   <li>Token bucket rate limiting for smooth request pacing in OPEN model
 *   <li>CountDownLatch for efficient synchronization
 *   <li>Detailed request execution logging with phase tracking
 *   <li>Back-pressure handling to prevent resource exhaustion
 * </ul>
 *
 * @author Maher
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
  private static final int SCHEDULER_THREAD_MULTIPLIER = 2;

  /**
   * Enum representing the different phases of load test execution.
   */
  public enum TestPhase {
    INITIALIZING,
    WARMUP,
    RAMP_UP,
    HOLD,
    COMPLETED,
    TERMINATED
  }

  /**
   * Record for logging detailed request execution information.
   */
  /**
   * Record for logging detailed request execution information.
   */
  public record RequestExecutionLog(
          Instant timestamp,
          TestPhase phase,
          int userId,
          String method,
          String path,
          long durationMs,
          boolean backPressured,
          boolean success,
          int statusCode
  ) {}



  private final TestPlanSpec testPlanSpec;
  private final LoadHttpClient httpClient;
  private final ExecutorService executorService;
  private final ScheduledExecutorService schedulerService;

  private final AtomicBoolean testRunning = new AtomicBoolean(false);
  private final AtomicReference<String> terminationReason = new AtomicReference<>();
  private final AtomicInteger activeRequests = new AtomicInteger(0);
  private final AtomicReference<TestPhase> currentPhase = new AtomicReference<>(TestPhase.INITIALIZING);

  // Efficient synchronization primitives
  private final CountDownLatch testCompletionLatch = new CountDownLatch(1);
  private CountDownLatch userCompletionLatch;

  // Rate limiting for OPEN model
  private RateLimiter rateLimiter;

  /**
   * Constructs a new LoadTestExecutionRunner with the specified test plan.
   *
   * @param testPlanSpec the test plan specification containing all test configuration
   * @throws IllegalArgumentException if testPlanSpec is null or invalid
   * @throws IllegalStateException if required configuration is missing
   */
  public LoadTestExecutionRunner(TestPlanSpec testPlanSpec) {
    this.testPlanSpec = testPlanSpec;

    var globalConfig = testPlanSpec.getTestSpec().getGlobalConfig();
    this.httpClient = new LoadHttpClient(
            globalConfig.getBaseUrl(),
            globalConfig.getTimeouts().getConnectionTimeoutMs() / 1000,
            globalConfig.getHeaders(),
            globalConfig.getVars());

    // Create virtual thread executor for lightweight concurrency
    this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    this.schedulerService = createSchedulerExecutor();

    log.info("LoadTestExecutionRunner initialized for {} model with virtual threads",
            testPlanSpec.getExecution().getLoadModel().getType());
  }

  /**
   * Creates an optimized scheduler executor based on CPU cores for timers and scheduling.
   *
   * @return configured ScheduledExecutorService
   */
  private ScheduledExecutorService createSchedulerExecutor() {
    int schedulerThreads = Math.max(3, Runtime.getRuntime().availableProcessors() / SCHEDULER_THREAD_MULTIPLIER);
    return Executors.newScheduledThreadPool(schedulerThreads);
  }

  /**
   * Executes a CLOSED workload model where a fixed number of virtual users each perform a specified
   * number of iterations across all scenarios and requests.
   *
   * <p>The execution follows this sequence:
   *
   * <ol>
   *   <li>Optional warmup phase
   *   <li>Ramp-up phase - users are started gradually
   *   <li>Hold phase - all users are active and performing iterations
   *   <li>Completion - when all iterations are done or hold time expires
   * </ol>
   *
   * @throws RuntimeException if execution fails or is interrupted
   */
  private void executeClosedWorkload() {
    var loadModel = testPlanSpec.getExecution().getLoadModel();

    var warmupDuration = parseDuration(loadModel.getWarmup());
    var rampUpDuration = parseDuration(loadModel.getRampUp());
    var holdDuration = parseDuration(loadModel.getHoldFor());

    int totalUsers = loadModel.getUsers();
    int iterationsPerUser = loadModel.getIterations();

    // Initialize user completion latch
    userCompletionLatch = new CountDownLatch(totalUsers);

    log.info("Executing CLOSED workload: {} users, {} iterations per user, ramp-up: {}s, hold: {}s",
            totalUsers, iterationsPerUser, rampUpDuration.getSeconds(), holdDuration.getSeconds());

    // Phase 1: Warmup
    if (warmupDuration.toMillis() > 0) {
      currentPhase.set(TestPhase.WARMUP);
      executeWarmup(warmupDuration);
    }

    if (!testRunning.get()) return;

    // Phase 2: Ramp-up and Hold
    currentPhase.set(TestPhase.RAMP_UP);
    var rampUpStart = Instant.now();
    var holdStart = rampUpStart.plus(rampUpDuration);
    var testEndTime = holdStart.plus(holdDuration);

    // Schedule phase transition to HOLD
    schedulerService.schedule(() -> {
      if (testRunning.get()) {
        currentPhase.set(TestPhase.HOLD);
      }
    }, rampUpDuration.toMillis(), TimeUnit.MILLISECONDS);

    // Schedule hold time termination
    scheduleHoldTimeTermination(testEndTime);

    // Start user threads with virtual threads
    for (int userId = 0; userId < totalUsers; userId++) {
      long userStartDelay = (userId * rampUpDuration.toMillis()) / totalUsers;

      final int finalUserId = userId;
      final long finalUserStartDelay = userStartDelay;
      final int finalIterationsPerUser = iterationsPerUser;

      executorService.submit(() -> {
        executeUserThread(finalUserId, finalUserStartDelay, finalIterationsPerUser, testEndTime);
      });
    }

    // Wait for all users to complete efficiently
    try {
      userCompletionLatch.await();

      // If we reach here, all iterations completed before hold time
      if (testRunning.get()) {
        terminateTest("ALL_ITERATIONS_COMPLETED");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (testRunning.get()) {
        log.warn("User completion wait interrupted");
      }
    }
  }

  /**
   * Executes a single user thread in a CLOSED workload model.
   *
   * @param userId the unique identifier for this user thread
   * @param startDelay the delay in milliseconds before this user should start
   * @param iterations the number of iterations this user should perform
   * @param testEndTime the absolute time when the test should end (hold time expiration)
   */
  private void executeUserThread(int userId, long startDelay, int iterations, Instant testEndTime) {
    try {
      // Wait for ramp-up delay
      if (startDelay > 0) {
        Thread.sleep(startDelay);
      }

      log.debug("User {} started with {} iterations (delay: {}ms)", userId, iterations, startDelay);

      int completedIterations = 0;
      for (int i = 0; i < iterations && testRunning.get(); i++) {
        // Check if hold time expired
        if (Instant.now().isAfter(testEndTime)) {
          log.debug("User {} stopping due to hold time expiration after {} iterations",
                  userId, completedIterations);
          break;
        }

        executeAllRequests(null, userId, false);
        completedIterations++;

        // Apply optimized think time between iterations (except for last iteration)
        if (i < iterations - 1) {
          applyThinkTime();
        }
      }

      log.debug("User {} completed {} out of {} iterations", userId, completedIterations, iterations);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("User {} interrupted", userId);
    } finally {
      userCompletionLatch.countDown(); // Signal completion
      log.debug("User {} finished", userId);
    }
  }

  /**
   * Applies optimized think time between requests using ThreadLocalRandom.
   *
   * @throws InterruptedException if the thread is interrupted during the delay
   */
  private void applyThinkTime() {
    var thinkTime = testPlanSpec.getExecution().getThinkTime();
    if (thinkTime == null) return;

    try {
      int delay;
      if (thinkTime.getType() == TestPlanSpec.ThinkTimeType.FIXED) {
        delay = thinkTime.getMin();
      } else {
        delay = ThreadLocalRandom.current().nextInt(thinkTime.getMin(), thinkTime.getMax() + 1);
      }

      if (delay > 0) {
        log.debug("Applying think time: {} ms", delay);
        Thread.sleep(delay);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Main execution method that orchestrates the entire load test.
   *
   * <p>This method focuses purely on test execution and coordination without metrics collection.
   * The execution is asynchronous and returns immediately with a CompletableFuture that will
   * complete when the test finishes.
   *
   * @return a CompletableFuture that completes when the test finishes
   * @throws RuntimeException if the test execution fails
   */
  public CompletableFuture<Void> execute() {
    return CompletableFuture.runAsync(() -> {
      try {
        log.info("Starting load test execution: {}", testPlanSpec.getTestSpec().getId());

        testRunning.set(true);
        currentPhase.set(TestPhase.INITIALIZING);
        var testStartTime = Instant.now();

        // Execute based on workload model
        var loadModel = testPlanSpec.getExecution().getLoadModel();
        if (loadModel.getType() == TestPlanSpec.WorkLoadModel.CLOSED) {
          executeClosedWorkload();
        } else {
          executeOpenWorkload();
        }

        // Wait for completion using efficient CountDownLatch
        waitForTestCompletion();

        var testEndTime = Instant.now();
        var totalDuration = Duration.between(testStartTime, testEndTime);

        currentPhase.set(TestPhase.COMPLETED);
        log.info("Load test completed. Duration: {}s, Reason: {}",
                totalDuration.getSeconds(), terminationReason.get());

      } catch (Exception e) {
        log.error("Load test execution failed", e);
        currentPhase.set(TestPhase.TERMINATED);
        terminateTest("EXECUTION_ERROR: " + e.getMessage());
        throw new RuntimeException("Load test execution failed", e);

      } finally {
        cleanup();
      }
    }, executorService);
  }

  /**
   * Executes an OPEN workload model using token bucket rate limiting for smooth request pacing.
   *
   * @throws RuntimeException if execution fails or is interrupted
   */
  private void executeOpenWorkload() {
    var loadModel = testPlanSpec.getExecution().getLoadModel();

    var warmupDuration = parseDuration(loadModel.getWarmup());
    var testDuration = parseDuration(loadModel.getDuration());

    int arrivalRate = loadModel.getArrivalRatePerSec();
    int maxConcurrent = loadModel.getMaxConcurrent();

    log.info("Executing OPEN workload: {} req/sec, max {} concurrent, duration: {}s",
            arrivalRate, maxConcurrent, testDuration.getSeconds());

    // Initialize rate limiter for smooth request pacing
    rateLimiter = RateLimiter.create(arrivalRate);

    // Phase 1: Warmup
    if (warmupDuration.toMillis() > 0) {
      currentPhase.set(TestPhase.WARMUP);
      executeWarmup(warmupDuration);
    }

    if (!testRunning.get()) return;

    // Phase 2: Main execution (HOLD phase for OPEN model)
    currentPhase.set(TestPhase.HOLD);
    var testEndTime = Instant.now().plus(testDuration);

    // Schedule duration-based termination
    scheduleDurationTermination(testDuration);

    // Concurrency control with semaphore for back-pressure
    var concurrencyLimiter = new Semaphore(maxConcurrent);

    // Main request generation loop with rate limiting
    while (testRunning.get() && Instant.now().isBefore(testEndTime)) {
      // Acquire rate limit permit (blocks if necessary for smooth pacing)
      rateLimiter.acquire();

      if (!testRunning.get() || Instant.now().isAfter(testEndTime)) {
        break;
      }

      if (concurrencyLimiter.tryAcquire()) {
        executorService.submit(() -> {
          executeAllRequests(concurrencyLimiter, -1, false);
        });
      } else {
        // Back-pressure: too many concurrent requests
        logBackPressureEvent();
      }
    }

    // Wait for remaining requests to complete
    waitForActiveRequestsToComplete(Duration.ofSeconds(30));

    if (testRunning.get()) {
      terminateTest("DURATION_COMPLETED");
    }
  }

  /**
   * Logs a back-pressure event when max concurrent requests is reached.
   */
  private void logBackPressureEvent() {
    log.debug("Back-pressure: max concurrent requests reached");
    // Log back-pressure event with empty request details
    var backPressureLog = new RequestExecutionLog(
            Instant.now(),
            currentPhase.get(),
            -1, // No specific user for back-pressure events
            "N/A",
            "N/A",
            0,
            true,
            false,
            0
    );
    log.info("Request execution: {}", backPressureLog);
  }

  /**
   * Executes the warmup phase to prepare the system for load testing.
   *
   * @param warmupDuration the duration of the warmup phase
   */
  private void executeWarmup(Duration warmupDuration) {
    log.info("Starting warmup phase for {} seconds", warmupDuration.getSeconds());

    var warmupEnd = Instant.now().plus(warmupDuration);

    var warmupTask = schedulerService.scheduleWithFixedDelay(() -> {
      if (!testRunning.get() || Instant.now().isAfter(warmupEnd)) {
        return;
      }

      try {
        executeAllRequests(null, -1, true); // warmup request
      } catch (Exception e) {
        log.debug("Warmup request failed: {}", e.getMessage());
      }
    }, 0, 1000, TimeUnit.MILLISECONDS); // 1 request per second during warmup

    // Wait for warmup to complete
    try {
      Thread.sleep(warmupDuration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      warmupTask.cancel(false);
    }

    log.info("Warmup phase completed");
  }

  /**
   * Executes all requests across all scenarios in the test plan.
   *
   * @param concurrencyLimiter semaphore for controlling maximum concurrent requests (can be null)
   * @param userId the user ID for CLOSED model tracking (-1 for OPEN model or warmup)
   * @param isWarmup true if this is a warmup request
   */
  private void executeAllRequests(Semaphore concurrencyLimiter, int userId, boolean isWarmup) {
    try {
      activeRequests.incrementAndGet();

      // Execute all scenarios and their requests
      for (var scenario : testPlanSpec.getTestSpec().getScenarios()) {
        if (!testRunning.get()) break;

        // Execute each request in the scenario
        for (var request : scenario.getRequests()) {
          if (!testRunning.get()) break;
          executeRequest(request, userId, isWarmup);
        }
      }

    } finally {
      activeRequests.decrementAndGet();

      if (concurrencyLimiter != null) {
        concurrencyLimiter.release();
      }
    }
  }

  /**
   * Executes a single HTTP request and logs the execution details.
   *
   * @param request the HTTP request to execute
   * @param userId the user ID for tracking (-1 for OPEN model or warmup)
   * @param isWarmup true if this is a warmup request
   */
  /**
   * Executes a single HTTP request and logs the execution details.
   *
   * @param request the HTTP request to execute
   * @param userId the user ID for tracking (-1 for OPEN model or warmup)
   * @param isWarmup true if this is a warmup request
   */
  private void executeRequest(TestPlanSpec.Request request, int userId, boolean isWarmup) {
    var requestStart = Instant.now();
    boolean success = false;
    int statusCode = 0;
    boolean backPressured = false;

    try {
      var response = httpClient.execute(request);
      var requestEnd = Instant.now();
      long responseTime = Duration.between(requestStart, requestEnd).toMillis();

      statusCode = response.getStatusCode();
      success = true;

      // Log request execution details
      var executionLog = new RequestExecutionLog(
              Instant.now(),
              currentPhase.get(),
              userId,
              request.getMethod().name(),
              request.getPath(),
              responseTime,
              backPressured,
              success,
              statusCode
      );

      if (!isWarmup) {
        log.info("Request execution: {}", executionLog);
      } else {
        log.debug("Warmup request execution: {}", executionLog);
      }

      log.debug("Request completed: {} ms, status: {}, user: {}", responseTime, statusCode, userId);

    } catch (Exception e) {
      var requestEnd = Instant.now();
      long responseTime = Duration.between(requestStart, requestEnd).toMillis();

      // Log failed request execution
      var executionLog = new RequestExecutionLog(
              Instant.now(),
              currentPhase.get(),
              userId,
              request.getMethod().name(),
              request.getPath(),
              responseTime,
              backPressured,
              success,
              statusCode
      );

      if (!isWarmup) {
        log.info("Request execution: {}", executionLog);
      } else {
        log.debug("Warmup request execution: {}", executionLog);
      }

      log.debug("Request failed: {}, user: {}", e.getMessage(), userId);
    }
  }

  /**
   * Schedules automatic test termination when the hold time expires.
   *
   * @param testEndTime the absolute time when the test should be terminated
   */
  private void scheduleHoldTimeTermination(Instant testEndTime) {
    long delayMs = Duration.between(Instant.now(), testEndTime).toMillis();

    schedulerService.schedule(() -> {
      if (testRunning.get()) {
        terminateTest("HOLD_TIME_EXPIRED");
      }
    }, delayMs, TimeUnit.MILLISECONDS);
  }

  /**
   * Schedules automatic test termination after the specified duration.
   *
   * @param testDuration the duration after which the test should be terminated
   */
  private void scheduleDurationTermination(Duration testDuration) {
    schedulerService.schedule(() -> {
      if (testRunning.get()) {
        terminateTest("DURATION_COMPLETED");
      }
    }, testDuration.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * Terminates the load test with the specified reason.
   *
   * @param reason a descriptive reason for the termination
   */
  @VisibleForTesting
  void terminateTest(String reason) {
    if (testRunning.compareAndSet(true, false)) {
      // Only set termination reason once
      terminationReason.compareAndSet(null, reason);
      testCompletionLatch.countDown(); // Signal completion efficiently
      log.info("Test termination initiated: {}", reason);
    }
  }

  /**
   * Waits for the test to complete using CountDownLatch for efficient blocking.
   */
  private void waitForTestCompletion() {
    try {
      testCompletionLatch.await(); // Efficient blocking without busy-waiting
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Waits for all active requests to complete with a specified timeout.
   *
   * @param timeout the maximum time to wait for requests to complete
   */
  @VisibleForTesting
  void waitForActiveRequestsToComplete(Duration timeout) {
    var deadline = Instant.now().plus(timeout);

    while (activeRequests.get() > 0 && Instant.now().isBefore(deadline)) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    if (activeRequests.get() > 0) {
      log.warn("Timeout waiting for {} active requests to complete", activeRequests.get());
    }
  }

  /**
   * Parses a duration string into a Duration object.
   *
   * @param duration the duration string to parse
   * @return a Duration object, or Duration.ZERO if input is null/empty
   */
  @VisibleForTesting
  Duration parseDuration(String duration) {
    if (duration == null || duration.trim().isEmpty()) {
      return Duration.ZERO;
    }

    var trimmed = duration.trim().toLowerCase();
    if (trimmed.endsWith("s")) {
      return Duration.ofSeconds(Integer.parseInt(trimmed.substring(0, trimmed.length() - 1)));
    } else if (trimmed.endsWith("m")) {
      return Duration.ofMinutes(Integer.parseInt(trimmed.substring(0, trimmed.length() - 1)));
    } else {
      // Assume seconds if no unit specified
      return Duration.ofSeconds(Integer.parseInt(trimmed));
    }
  }

  /**
   * Performs comprehensive cleanup of all resources used during test execution.
   */
  public void cleanup() {
    log.info("Starting load test cleanup...");

    try {
      testRunning.set(false);

      // Shutdown scheduler service first (stops creating new tasks)
      shutdownExecutorService("Scheduler", schedulerService, 5);

      // Shutdown main executor service
      shutdownExecutorService("Main Executor", executorService, 10);

      // Close HTTP client
      try {
        httpClient.close();
      } catch (Exception e) {
        log.warn("Error closing HTTP client: {}", e.getMessage());
      }

      log.info("Load test cleanup completed successfully");

    } catch (Exception e) {
      log.error("Unexpected error during cleanup", e);
    }
  }

  /**
   * Shuts down an executor service gracefully with proper timeout handling.
   *
   * @param name descriptive name for the executor (used in logging)
   * @param executor the executor service to shutdown
   * @param timeoutSeconds maximum time to wait for graceful shutdown
   */
  private void shutdownExecutorService(String name, ExecutorService executor, int timeoutSeconds) {
    try {
      log.debug("Shutting down {} executor service...", name);

      // Initiate shutdown
      executor.shutdown();

      // Wait for existing tasks to complete
      if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
        log.warn("{} executor did not terminate within {} seconds, forcing shutdown",
                name, timeoutSeconds);

        // Force shutdown
        executor.shutdownNow();

        // Wait a bit more for tasks to respond to being cancelled
        if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
          log.warn("{} executor did not terminate even after forced shutdown", name);
        }
      } else {
        log.debug("{} executor service shut down successfully", name);
      }

    } catch (InterruptedException e) {
      // Current thread was interrupted while waiting
      log.warn("{} executor shutdown was interrupted, forcing immediate shutdown", name);

      // Preserve interrupt status
      Thread.currentThread().interrupt();

      // Force shutdown
      executor.shutdownNow();
    } catch (Exception e) {
      log.error("Unexpected error shutting down {} executor", name, e);

      // Force shutdown as fallback
      try {
        executor.shutdownNow();
      } catch (Exception shutdownException) {
        log.error("Failed to force shutdown {} executor", name, shutdownException);
      }
    }
  }
}