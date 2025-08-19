package org.example.load;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.example.client.LoadHttpClient;
import org.example.client.SLAMonitor;
import org.example.client.SLAViolation;
import org.example.client.enums.TestPhase;
import org.example.client.metrics.ComprehensiveTestReport;
import org.example.client.metrics.TestMetrics;
import org.example.client.response.RestResponseData;
import org.example.dto.TestPlanSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enhanced Load Test Execution Runner that manages and executes load tests with proper resource
 * cleanup and exception handling.
 *
 * <p>This class supports both CLOSED and OPEN workload models:
 *
 * <ul>
 *   <li><b>CLOSED Model:</b> Fixed number of virtual users, each performing a specified number of
 *       iterations
 *   <li><b>OPEN Model:</b> Arrival rate-based testing with maximum concurrency limits
 * </ul>
 *
 * <p>Features include:
 *
 * <ul>
 *   <li>Multi-phase test execution (warmup, ramp-up, hold, completion)
 *   <li>Real-time SLA monitoring and violation handling
 *   <li>Comprehensive metrics collection and reporting
 *   <li>Thread-safe execution with proper resource management
 *   <li>Configurable think times and back-pressure handling
 * </ul>
 *
 * <p>Thread Safety: This class is thread-safe and uses concurrent data structures and atomic
 * operations for state management.
 *
 * @author Load Testing Framework
 * @version 1.0
 * @since 1.0
 */
public class LoadTestExecutionRunner {
  private static final Logger log = LoggerFactory.getLogger(LoadTestExecutionRunner.class);

  private final TestPlanSpec testPlanSpec;
  private final LoadHttpClient httpClient;
  private final ExecutorService executorService;
  private final ScheduledExecutorService schedulerService;

  // Test state management
  private final AtomicBoolean testRunning = new AtomicBoolean(false);
  private final AtomicBoolean testCompleted = new AtomicBoolean(false);
  private final AtomicReference<String> terminationReason = new AtomicReference<>();

  // Enhanced metrics system
  private final TestMetrics metrics;
  private final SLAMonitor slaMonitor;

  /**
   * Constructs a new LoadTestExecutionRunner with the specified test plan.
   *
   * <p>Initializes all required components including:
   *
   * <ul>
   *   <li>HTTP client configured with global settings
   *   <li>Thread pools sized based on workload model
   *   <li>Metrics collection system
   *   <li>SLA monitoring system
   * </ul>
   *
   * @param testPlanSpec the test plan specification containing all test configuration
   * @throws IllegalArgumentException if testPlanSpec is null or invalid
   * @throws IllegalStateException if required configuration is missing
   */
  public LoadTestExecutionRunner(TestPlanSpec testPlanSpec) {
    this.testPlanSpec = testPlanSpec;

    // Initialize HTTP client with global config
    var globalConfig = testPlanSpec.getTestSpec().getGlobalConfig();
    this.httpClient =
        new LoadHttpClient(
            globalConfig.getBaseUrl(),
            globalConfig.getTimeouts().getConnectionTimeoutMs() / 1000, // convert to seconds
            globalConfig.getHeaders(),
            globalConfig.getVars());

    // Initialize thread pools
    int maxThreads = calculateMaxThreads();
    this.executorService = Executors.newFixedThreadPool(maxThreads);
    this.schedulerService = Executors.newScheduledThreadPool(6); // Increased for metrics

    // Initialize enhanced metrics system
    this.metrics =
        new TestMetrics(schedulerService, testPlanSpec.getExecution().getLoadModel().getType());
    this.slaMonitor = new SLAMonitor(testPlanSpec.getExecution().getGlobalSla(), metrics);

    log.info(
        "LoadTestExecutionRunner initialized for {} model with {} max threads",
        testPlanSpec.getExecution().getLoadModel().getType(),
        maxThreads);
  }

  /**
   * Executes a CLOSED workload model where a fixed number of virtual users each perform a specified
   * number of iterations.
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
   * <p>Each user thread:
   *
   * <ul>
   *   <li>Waits for its designated start delay (ramp-up)
   *   <li>Performs all assigned iterations
   *   <li>Applies think time between requests if configured
   *   <li>Stops when iterations complete or hold time expires
   * </ul>
   *
   * @throws RuntimeException if execution fails or is interrupted
   */
  private void executeClosedWorkload() {
    var loadModel = testPlanSpec.getExecution().getLoadModel();

    Duration warmupDuration = parseDuration(loadModel.getWarmup());
    Duration rampUpDuration = parseDuration(loadModel.getRampUp());
    Duration holdDuration = parseDuration(loadModel.getHoldFor());

    int totalUsers = loadModel.getUsers();
    int iterationsPerUser = loadModel.getIterations(); // FIXED: Each user does ALL iterations

    log.info(
        "Executing CLOSED workload: {} users, {} iterations per user (total: {}), ramp-up: {}s, hold: {}s",
        totalUsers,
        iterationsPerUser,
        totalUsers * iterationsPerUser,
        rampUpDuration.getSeconds(),
        holdDuration.getSeconds());

    // Phase 1: Warmup
    if (warmupDuration.toMillis() > 0) {
      metrics.setPhase(TestPhase.WARMUP);
      executeWarmup(warmupDuration);
    }

    if (!testRunning.get()) return;

    // Phase 2: Ramp-up and Hold
    metrics.setPhase(TestPhase.RAMP_UP);
    Instant rampUpStart = Instant.now();
    Instant holdStart = rampUpStart.plus(rampUpDuration);
    Instant testEndTime = holdStart.plus(holdDuration);

    // Schedule phase transitions
    schedulerService.schedule(
        () -> {
          if (testRunning.get()) {
            metrics.setPhase(TestPhase.HOLD);
          }
        },
        rampUpDuration.toMillis(),
        TimeUnit.MILLISECONDS);

    // Schedule hold time termination
    scheduleHoldTimeTermination(testEndTime);

    // Create user tasks
    List<CompletableFuture<Void>> userTasks = new ArrayList<>();

    for (int userId = 0; userId < totalUsers; userId++) {
      long userStartDelay = (userId * rampUpDuration.toMillis()) / totalUsers;

      // Make variables effectively final for lambda
      final int finalUserId = userId;
      final long finalUserStartDelay = userStartDelay;
      final int finalIterationsPerUser = iterationsPerUser; // Each user gets ALL iterations

      CompletableFuture<Void> userTask =
          CompletableFuture.runAsync(
              () -> {
                executeUserThread(
                    finalUserId, finalUserStartDelay, finalIterationsPerUser, testEndTime);
              },
              executorService);

      userTasks.add(userTask);
    }

    // Wait for all users to complete or test to be terminated
    CompletableFuture<Void> allUserTasks =
        CompletableFuture.allOf(userTasks.toArray(new CompletableFuture[0]));

    try {
      allUserTasks.get();

      // If we reach here, all iterations completed before hold time
      if (testRunning.get()) {
        terminateTest("ALL_ITERATIONS_COMPLETED");
      }
    } catch (Exception e) {
      if (testRunning.get()) {
        log.warn("User tasks interrupted: {}", e.getMessage());
      }
    }
  }

  /**
   * Executes a single user thread in a CLOSED workload model.
   *
   * <p>This method handles the lifecycle of a single virtual user:
   *
   * <ul>
   *   <li>Waits for the designated start delay (ramp-up timing)
   *   <li>Registers user start with metrics
   *   <li>Performs the specified number of iterations
   *   <li>Applies think time between requests
   *   <li>Stops on hold time expiration or test termination
   *   <li>Registers user completion with metrics
   * </ul>
   *
   * @param userId the unique identifier for this user thread
   * @param startDelay the delay in milliseconds before this user should start
   * @param iterations the number of iterations this user should perform
   * @param testEndTime the absolute time when the test should end (hold time expiration)
   * @throws InterruptedException if the thread is interrupted during execution
   */
  private void executeUserThread(int userId, long startDelay, int iterations, Instant testEndTime) {
    try {
      // Wait for ramp-up delay
      if (startDelay > 0) {
        Thread.sleep(startDelay);
      }

      metrics.userStarted(userId);
      log.debug("User {} started with {} iterations (delay: {}ms)", userId, iterations, startDelay);

      int completedIterations = 0;
      for (int i = 0; i < iterations && testRunning.get(); i++) {
        // Check if hold time expired
        if (Instant.now().isAfter(testEndTime)) {
          log.debug(
              "User {} stopping due to hold time expiration after {} iterations",
              userId,
              completedIterations);
          break;
        }

        executeRequest(null, userId, false);
        completedIterations++;

        // Apply think time between requests (except for last iteration)
        if (i < iterations - 1) {
          applyThinkTime();
        }
      }

      log.debug(
          "User {} completed {} out of {} iterations", userId, completedIterations, iterations);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("User {} interrupted", userId);
    } finally {
      metrics.userCompleted(userId);
      log.debug("User {} finished", userId);
    }
  }

  /**
   * Applies think time between requests as specified in the test configuration.
   *
   * <p>Think time simulates the delay a real user would have between actions. Supports two modes:
   *
   * <ul>
   *   <li><b>FIXED:</b> Always uses the minimum value
   *   <li><b>RANDOM:</b> Random delay between min and max values
   * </ul>
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
        // RANDOM think time between min and max
        Random random = new Random();
        delay = thinkTime.getMin() + random.nextInt(thinkTime.getMax() - thinkTime.getMin() + 1);
      }

      if (delay > 0) {
        log.trace("Applying think time: {}ms", delay);
        Thread.sleep(delay);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Main execution method that orchestrates the entire load test.
   *
   * <p>This method:
   *
   * <ol>
   *   <li>Initializes test state and metrics
   *   <li>Starts SLA monitoring
   *   <li>Delegates to the appropriate workload execution method
   *   <li>Waits for test completion
   *   <li>Generates and returns a comprehensive test report
   *   <li>Ensures proper cleanup regardless of success or failure
   * </ol>
   *
   * <p>The execution is asynchronous and returns immediately with a CompletableFuture that will
   * complete when the test finishes.
   *
   * @return a CompletableFuture that completes with a comprehensive test report
   * @throws RuntimeException if the test execution fails
   */
  public CompletableFuture<ComprehensiveTestReport> execute() {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            log.info("Starting load test execution: {}", testPlanSpec.getTestSpec().getId());

            testRunning.set(true);
            Instant testStartTime = Instant.now();
            metrics.setPhase(TestPhase.INITIALIZING);

            // Start SLA monitoring
            startSLAMonitoring();

            // Execute based on workload model
            var loadModel = testPlanSpec.getExecution().getLoadModel();
            if (loadModel.getType() == TestPlanSpec.WorkLoadModel.CLOSED) {
              executeClosedWorkload();
            } else {
              executeOpenWorkload();
            }

            // Wait for completion or termination
            waitForTestCompletion();

            Instant testEndTime = Instant.now();
            Duration totalDuration = Duration.between(testStartTime, testEndTime);

            metrics.setPhase(TestPhase.COMPLETED);

            log.info(
                "Load test completed. Duration: {}s, Reason: {}",
                totalDuration.getSeconds(),
                terminationReason.get());

            return generateFinalReport(testEndTime);

          } catch (Exception e) {
            log.error("Load test execution failed", e);
            metrics.setPhase(TestPhase.TERMINATED);
            terminateTest("EXECUTION_ERROR: " + e.getMessage());
            throw new RuntimeException("Load test execution failed", e);

          } finally {
            cleanup();
          }
        },
        executorService);
  }

  /**
   * Executes an OPEN workload model based on arrival rate and maximum concurrency.
   *
   * <p>The OPEN model simulates real-world traffic patterns where requests arrive at a specified
   * rate regardless of response times. Key characteristics:
   *
   * <ul>
   *   <li>Fixed arrival rate (requests per second)
   *   <li>Maximum concurrency limit to prevent resource exhaustion
   *   <li>Back-pressure handling when concurrency limit is reached
   *   <li>Duration-based execution (not iteration-based)
   * </ul>
   *
   * <p>Execution phases:
   *
   * <ol>
   *   <li>Optional warmup phase
   *   <li>Main execution at target arrival rate
   *   <li>Graceful completion of remaining requests
   * </ol>
   *
   * @throws RuntimeException if execution fails or is interrupted
   */
  private void executeOpenWorkload() {
    var loadModel = testPlanSpec.getExecution().getLoadModel();

    Duration warmupDuration = parseDuration(loadModel.getWarmup());
    Duration testDuration = parseDuration(loadModel.getDuration());

    int arrivalRate = loadModel.getArrivalRatePerSec();
    int maxConcurrent = loadModel.getMaxConcurrent();

    log.info(
        "Executing OPEN workload: {} req/sec, max {} concurrent, duration: {}s",
        arrivalRate,
        maxConcurrent,
        testDuration.getSeconds());

    // Phase 1: Warmup
    if (warmupDuration.toMillis() > 0) {
      metrics.setPhase(TestPhase.WARMUP);
      executeWarmup(warmupDuration);
    }

    if (!testRunning.get()) return;

    // Phase 2: Main execution
    metrics.setPhase(TestPhase.HOLD); // OPEN model goes straight to HOLD
    Instant testEndTime = Instant.now().plus(testDuration);

    // Schedule duration-based termination
    scheduleDurationTermination(testDuration);

    // Rate controller with semaphore for concurrency limiting
    Semaphore concurrencyLimiter = new Semaphore(maxConcurrent);
    long intervalMicros = 1_000_000L / arrivalRate; // microseconds between requests

    ScheduledFuture<?> rateTask =
        schedulerService.scheduleAtFixedRate(
            () -> {
              if (!testRunning.get() || Instant.now().isAfter(testEndTime)) {
                return;
              }

              if (concurrencyLimiter.tryAcquire()) {
                metrics.incrementScheduledRequests();

                CompletableFuture<Void> requestTask =
                    CompletableFuture.runAsync(
                        () -> {
                          executeRequest(concurrencyLimiter, -1, false); // -1 = no specific user
                        },
                        executorService);

                // Handle request completion
                requestTask.whenComplete(
                    (result, throwable) -> {
                      if (throwable != null) {
                        log.debug("Request execution failed: {}", throwable.getMessage());
                      }
                    });
              } else {
                // Back-pressure: too many concurrent requests
                metrics.incrementBackPressureEvents();
                log.debug("Back-pressure: max concurrent requests reached");
              }
            },
            0,
            intervalMicros,
            TimeUnit.MICROSECONDS);

    // Wait for test completion
    try {
      while (testRunning.get() && Instant.now().isBefore(testEndTime)) {
        Thread.sleep(100);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      rateTask.cancel(false);
    }

    // Wait for remaining requests to complete
    waitForActiveRequestsToComplete(Duration.ofSeconds(30));

    if (testRunning.get()) {
      terminateTest("DURATION_COMPLETED");
    }
  }

  /**
   * Executes the warmup phase to prepare the system for load testing.
   *
   * <p>The warmup phase helps to:
   *
   * <ul>
   *   <li>Initialize connection pools
   *   <li>Warm up JVMs and caches
   *   <li>Identify initial system issues
   *   <li>Establish baseline performance
   * </ul>
   *
   * <p>During warmup, requests are sent at a low rate (1 req/sec) and their results are not
   * included in the final test metrics.
   *
   * @param warmupDuration the duration of the warmup phase
   * @throws InterruptedException if the warmup is interrupted
   */
  private void executeWarmup(Duration warmupDuration) {
    log.info("Starting warmup phase for {} seconds", warmupDuration.getSeconds());

    Instant warmupEnd = Instant.now().plus(warmupDuration);

    ScheduledFuture<?> warmupTask =
        schedulerService.scheduleAtFixedRate(
            () -> {
              if (!testRunning.get() || Instant.now().isAfter(warmupEnd)) {
                return;
              }

              try {
                executeRequest(null, -1, true); // warmup request
              } catch (Exception e) {
                log.debug("Warmup request failed: {}", e.getMessage());
              }
            },
            0,
            1000,
            TimeUnit.MILLISECONDS); // 1 request per second during warmup

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
   * Executes a single HTTP request and records the results.
   *
   * <p>This method:
   *
   * <ul>
   *   <li>Increments active request counter
   *   <li>Executes the HTTP request using the configured client
   *   <li>Measures response time
   *   <li>Records success/failure metrics (unless warmup)
   *   <li>Handles exceptions and error recording
   *   <li>Manages concurrency control (if provided)
   * </ul>
   *
   * @param concurrencyLimiter semaphore for controlling maximum concurrent requests (can be null)
   * @param userId the user ID for CLOSED model tracking (-1 for OPEN model or warmup)
   * @param isWarmup true if this is a warmup request (metrics won't be recorded)
   */
  private void executeRequest(Semaphore concurrencyLimiter, int userId, boolean isWarmup) {
    try {
      metrics.incrementActiveRequests();

      // Get first scenario and request (simplified for example)
      var scenario = testPlanSpec.getTestSpec().getScenarios().get(0);
      var request = scenario.getRequests();

      Instant requestStart = Instant.now();
      RestResponseData response = httpClient.execute(request);
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

      log.debug(
          "Request completed: {} ms, status: {}, user: {}",
          responseTime,
          response.getStatusCode(),
          userId);

    } catch (Exception e) {
      if (!isWarmup) {
        if (userId >= 0) {
          metrics.recordError(e.getMessage(), e, userId);
        } else {
          metrics.recordError(e.getMessage(), e);
        }
      }
      log.debug("Request failed: {}, user: {}", e.getMessage(), userId);
    } finally {
      metrics.decrementActiveRequests();

      if (concurrencyLimiter != null) {
        concurrencyLimiter.release();
      }
    }
  }

  /**
   * Starts the SLA (Service Level Agreement) monitoring system.
   *
   * <p>The SLA monitor runs on a separate scheduled thread and periodically checks if the current
   * test metrics violate any configured SLAs. Supported SLA metrics include:
   *
   * <ul>
   *   <li>Error rate percentage
   *   <li>P95 response time
   *   <li>P99 response time
   * </ul>
   *
   * <p>When violations are detected:
   *
   * <ul>
   *   <li>Violation details are logged
   *   <li>Metrics are recorded
   *   <li>Configured action is taken (CONTINUE or STOP)
   * </ul>
   *
   * <p>If no SLA configuration is present, monitoring is skipped.
   */
  private void startSLAMonitoring() {
    if (testPlanSpec.getExecution().getGlobalSla() == null) {
      return;
    }

    log.info("Starting SLA monitoring");

    schedulerService.scheduleAtFixedRate(
        () -> {
          if (!testRunning.get()) return;

          SLAViolation violation = slaMonitor.checkSLA();
          if (violation != null) {
            log.warn("SLA violation detected: {}", violation.getReason());

            // Record SLA violation in metrics
            var sla = testPlanSpec.getExecution().getGlobalSla();
            String violationType;
            double actualValue;
            double threshold;

            if (violation.getReason().contains("Error rate")) {
              violationType = "ERROR_RATE";
              actualValue = metrics.getErrorRate();
              threshold = sla.getErrorRatePct();
            } else if (violation.getReason().contains("P95 response time")) {
              violationType = "P95_RESPONSE_TIME";
              actualValue = metrics.getP95ResponseTime();
              threshold = sla.getP95LtMs();
            } else {
              violationType = "UNKNOWN";
              actualValue = 0;
              threshold = 0;
            }

            var onError = sla.getOnError();
            String action = onError != null ? onError.getAction().name() : "UNKNOWN";

            metrics.recordSLAViolation(violationType, actualValue, threshold, action);

            if (onError != null && onError.getAction() == TestPlanSpec.OnErrorAction.STOP) {
              terminateTest("SLA_VIOLATION: " + violation.getReason());
            } else {
              log.info("SLA violation detected but continuing due to OnError.CONTINUE");
            }
          }
        },
        5,
        5,
        TimeUnit.SECONDS); // Check SLA every 5 seconds
  }

  /**
   * Schedules automatic test termination when the hold time expires.
   *
   * <p>This is used in CLOSED workload model to ensure the test doesn't run indefinitely if users
   * complete their iterations quickly.
   *
   * @param testEndTime the absolute time when the test should be terminated
   */
  private void scheduleHoldTimeTermination(Instant testEndTime) {
    long delayMs = Duration.between(Instant.now(), testEndTime).toMillis();

    schedulerService.schedule(
        () -> {
          if (testRunning.get()) {
            terminateTest("HOLD_TIME_EXPIRED");
          }
        },
        delayMs,
        TimeUnit.MILLISECONDS);
  }

  /**
   * Schedules automatic test termination after the specified duration.
   *
   * <p>This is used in OPEN workload model to terminate the test after the configured duration
   * regardless of request completion status.
   *
   * @param testDuration the duration after which the test should be terminated
   */
  private void scheduleDurationTermination(Duration testDuration) {
    schedulerService.schedule(
        () -> {
          if (testRunning.get()) {
            terminateTest("DURATION_COMPLETED");
          }
        },
        testDuration.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  /**
   * Terminates the load test with the specified reason.
   *
   * <p>This method is thread-safe and ensures that termination only happens once. It sets the
   * appropriate flags to stop all running threads and records the termination reason for reporting.
   *
   * @param reason a descriptive reason for the termination (e.g., "SLA_VIOLATION",
   *     "DURATION_COMPLETED")
   */
  private void terminateTest(String reason) {
    if (testRunning.compareAndSet(true, false)) {
      terminationReason.set(reason);
      testCompleted.set(true);
      log.info("Test termination initiated: {}", reason);
    }
  }

  /**
   * Waits for the test to complete by monitoring the completion flag.
   *
   * <p>This method blocks the calling thread until either:
   *
   * <ul>
   *   <li>The test completes normally
   *   <li>The test is terminated due to SLA violations or other reasons
   *   <li>The thread is interrupted
   * </ul>
   *
   * @throws InterruptedException if the waiting thread is interrupted
   */
  private void waitForTestCompletion() {
    try {
      while (!testCompleted.get()) {
        Thread.sleep(100);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Waits for all active requests to complete with a specified timeout.
   *
   * <p>This method is called during test shutdown to allow in-flight requests to complete
   * gracefully before forcing termination. It prevents data loss and ensures accurate metrics
   * collection.
   *
   * @param timeout the maximum time to wait for requests to complete
   */
  private void waitForActiveRequestsToComplete(Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);

    while (metrics.getActiveRequests().get() > 0 && Instant.now().isBefore(deadline)) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    if (metrics.getActiveRequests().get() > 0) {
      log.warn("Timeout waiting for {} active requests to complete", metrics.getActiveRequests());
    }
  }

  /**
   * Parses a duration string into a Duration object.
   *
   * <p>Supported formats:
   *
   * <ul>
   *   <li>"30s" - 30 seconds
   *   <li>"5m" - 5 minutes
   *   <li>"120" - 120 seconds (default unit)
   * </ul>
   *
   * @param duration the duration string to parse
   * @return a Duration object, or Duration.ZERO if input is null/empty
   * @throws NumberFormatException if the duration string is not a valid number
   */
  private Duration parseDuration(String duration) {
    if (duration == null || duration.trim().isEmpty()) {
      return Duration.ZERO;
    }

    String trimmed = duration.trim().toLowerCase();
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
   * Calculates the maximum number of threads needed for the test execution.
   *
   * <p>The calculation depends on the workload model:
   *
   * <ul>
   *   <li><b>CLOSED:</b> Number of users + overhead for management threads
   *   <li><b>OPEN:</b> Maximum concurrent requests + overhead for management threads
   * </ul>
   *
   * <p>The overhead accounts for scheduler threads, metrics collection, and other background tasks.
   *
   * @return the maximum number of threads required for execution
   */
  private int calculateMaxThreads() {
    var loadModel = testPlanSpec.getExecution().getLoadModel();
    if (loadModel.getType() == TestPlanSpec.WorkLoadModel.CLOSED) {
      return loadModel.getUsers() + 10; // users + overhead
    } else {
      return loadModel.getMaxConcurrent() + 10; // max concurrent + overhead
    }
  }

  /**
   * Generates a comprehensive test report containing all metrics and analysis.
   *
   * <p>The report includes:
   *
   * <ul>
   *   <li>Response time statistics (min, max, average, percentiles)
   *   <li>Throughput metrics (average, peak)
   *   <li>Error analysis and status code distribution
   *   <li>SLA violation summary
   *   <li>User metrics (for CLOSED model)
   *   <li>Time window analysis
   *   <li>Test metadata and termination reason
   * </ul>
   *
   * @param testEndTime the time when the test completed
   * @return a comprehensive test report with all collected metrics
   */
  private ComprehensiveTestReport generateFinalReport(Instant testEndTime) {
    log.info("Generating comprehensive test report...");

    ComprehensiveTestReport report =
        metrics.generateComprehensiveReport(testEndTime, terminationReason.get());

    // Log summary
    logTestSummary(report);

    return report;
  }

  /**
   * Logs a detailed test summary to the console for immediate visibility.
   *
   * <p>The summary includes key metrics formatted for easy reading:
   *
   * <ul>
   *   <li>Test metadata (ID, duration, termination reason)
   *   <li>Request statistics (total, success rate, error rate)
   *   <li>Response time metrics (average, percentiles, min/max)
   *   <li>Throughput statistics
   *   <li>Status code distribution
   *   <li>SLA violations (if any)
   *   <li>User metrics for CLOSED model
   *   <li>Window analysis summary
   * </ul>
   *
   * @param report the comprehensive test report to summarize
   */
  private void logTestSummary(ComprehensiveTestReport report) {
    StringBuilder summary = new StringBuilder();
    summary.append("\n");
    summary.append("================== LOAD TEST SUMMARY ==================\n");
    summary.append(String.format("Test ID: %s\n", testPlanSpec.getTestSpec().getId()));
    summary.append(String.format("Duration: %d seconds\n", report.getTotalDuration().getSeconds()));
    summary.append(String.format("Termination: %s\n", report.getTerminationReason()));
    summary.append(String.format("Final Phase: %s\n", report.getFinalPhase()));
    summary.append("\n");

    // Request metrics
    summary.append("--- REQUEST METRICS ---\n");
    summary.append(String.format("Total Requests: %d\n", report.getTotalRequests()));
    summary.append(
        String.format(
            "Successful: %d (%.2f%%)\n",
            report.getSuccessfulRequests(),
            report.getTotalRequests() > 0
                ? (double) report.getSuccessfulRequests() / report.getTotalRequests() * 100
                : 0));
    summary.append(
        String.format("Failed: %d (%.2f%%)\n", report.getFailedRequests(), report.getErrorRate()));
    summary.append("\n");

    // Response time metrics
    summary.append("--- RESPONSE TIME METRICS ---\n");
    summary.append(String.format("Average: %.2f ms\n", report.getAverageResponseTime()));
    summary.append(String.format("P50: %d ms\n", report.getP50ResponseTime()));
    summary.append(String.format("P95: %d ms\n", report.getP95ResponseTime()));
    summary.append(String.format("P99: %d ms\n", report.getP99ResponseTime()));
    summary.append(String.format("Min: %d ms\n", report.getMinResponseTime()));
    summary.append(String.format("Max: %d ms\n", report.getMaxResponseTime()));
    summary.append("\n");

    // Throughput
    summary.append("--- THROUGHPUT ---\n");
    summary.append(String.format("Average: %.2f req/sec\n", report.getAverageThroughput()));
    summary.append(String.format("Peak: %.2f req/sec\n", report.getPeakThroughput()));
    summary.append("\n");

    // Status codes
    if (!report.getStatusCodeDistribution().isEmpty()) {
      summary.append("--- STATUS CODE DISTRIBUTION ---\n");
      report.getStatusCodeDistribution().entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              entry ->
                  summary.append(
                      String.format("%d: %d requests\n", entry.getKey(), entry.getValue())));
      summary.append("\n");
    }

    // SLA violations
    if (report.getSlaViolationSummary() != null
        && report.getSlaViolationSummary().getTotalViolations() > 0) {
      summary.append("--- SLA VIOLATIONS ---\n");
      summary.append(
          String.format(
              "Total Violations: %d\n", report.getSlaViolationSummary().getTotalViolations()));
      report
          .getSlaViolationSummary()
          .getViolationsByType()
          .forEach(
              (type, count) -> summary.append(String.format("%s: %d violations\n", type, count)));
      summary.append("\n");
    }

    // User metrics for CLOSED model
    if (report.getUserMetricsSummary() != null) {
      var userSummary = report.getUserMetricsSummary();
      summary.append("--- USER METRICS (CLOSED MODEL) ---\n");
      summary.append(String.format("Total Users: %d\n", userSummary.getTotalUsers()));
      summary.append(String.format("Max Concurrent: %d\n", userSummary.getMaxConcurrentUsers()));
      summary.append("\n");
    }

    // Window analysis
    if (report.getWindowAnalysis() != null) {
      var windowAnalysis = report.getWindowAnalysis();
      summary.append("--- WINDOW ANALYSIS ---\n");
      summary.append(String.format("Total Windows: %d\n", windowAnalysis.getTotalWindows()));
      summary.append(
          String.format(
              "Throughput: Avg=%.2f, Max=%.2f req/sec\n",
              windowAnalysis.getThroughputStats().getAverage(),
              windowAnalysis.getThroughputStats().getMax()));
      if (windowAnalysis.getResponseTimeStats().getCount() > 0) {
        summary.append(
            String.format(
                "Response Time: Avg=%.2f, Max=%.2f ms\n",
                windowAnalysis.getResponseTimeStats().getAverage(),
                windowAnalysis.getResponseTimeStats().getMax()));
      }
      summary.append("\n");
    }

    summary.append("======================================================\n");

    log.info(summary.toString());
  }

  /**
   * Performs comprehensive cleanup of all resources used during test execution.
   *
   * <p>This method ensures proper shutdown in the following order:
   *
   * <ol>
   *   <li>Stop test execution flag
   *   <li>Shutdown metrics system (to capture final window)
   *   <li>Shutdown scheduler service (stops creating new tasks)
   *   <li>Shutdown main executor service (waits for user threads)
   *   <li>Close HTTP client and connection pools
   * </ol>
   *
   * <p>Each shutdown step has proper error handling to ensure cleanup continues even if individual
   * components fail to shutdown gracefully.
   *
   * <p>This method should always be called after test execution, preferably in a finally block to
   * ensure resources are released regardless of how the test terminates.
   */
  public void cleanup() {
    log.info("Starting load test cleanup...");

    try {
      testRunning.set(false);

      // Shutdown metrics first to ensure final window is captured
      try {
        metrics.shutdown();
      } catch (Exception e) {
        log.warn("Error shutting down metrics: {}", e.getMessage());
      }

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
   * <p>The shutdown process follows best practices:
   *
   * <ol>
   *   <li>Call shutdown() to stop accepting new tasks
   *   <li>Wait for existing tasks to complete (up to timeout)
   *   <li>If timeout exceeded, call shutdownNow() to interrupt tasks
   *   <li>Wait briefly for interrupted tasks to respond
   *   <li>Log warnings if forceful shutdown was required
   * </ol>
   *
   * <p>This method handles InterruptedException properly by preserving the interrupt status and
   * ensuring forceful shutdown occurs.
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
        log.warn(
            "{} executor did not terminate within {} seconds, forcing shutdown",
            name,
            timeoutSeconds);

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
