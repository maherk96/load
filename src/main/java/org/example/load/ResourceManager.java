package org.example.load;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ResourceManager {

  private static final Logger log = LoggerFactory.getLogger(ResourceManager.class);
  private static final int SCHEDULER_THREAD_MULTIPLIER = 2;

  private final ExecutorService mainExecutor;
  private final ScheduledExecutorService schedulerService;

  public ResourceManager() {
    this.mainExecutor = Executors.newVirtualThreadPerTaskExecutor();
    this.schedulerService = createSchedulerExecutor();
  }

  private ScheduledExecutorService createSchedulerExecutor() {
    int schedulerThreads = Math.max(3, Runtime.getRuntime().availableProcessors() / SCHEDULER_THREAD_MULTIPLIER);
    return Executors.newScheduledThreadPool(schedulerThreads);
  }

  public ExecutorService getMainExecutor() {
    return mainExecutor;
  }

  public ScheduledExecutorService getSchedulerService() {
    return schedulerService;
  }

  public void cleanup() {
    shutdownExecutorService("Scheduler", schedulerService, 5);
    shutdownExecutorService("Main Executor", mainExecutor, 10);
  }

  private void shutdownExecutorService(String name, ExecutorService executor, int timeoutSeconds) {
    try {
      log.debug("Shutting down {} executor service...", name);
      executor.shutdown();

      if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
        log.warn("{} executor did not terminate within {} seconds, forcing shutdown", name, timeoutSeconds);
        executor.shutdownNow();

        if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
          log.warn("{} executor did not terminate even after forced shutdown", name);
        }
      } else {
        log.debug("{} executor service shut down successfully", name);
      }
    } catch (InterruptedException e) {
      log.warn("{} executor shutdown was interrupted, forcing immediate shutdown", name);
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    } catch (Exception e) {
      log.error("Unexpected error shutting down {} executor", name, e);
      try {
        executor.shutdownNow();
      } catch (Exception shutdownException) {
        log.error("Failed to force shutdown {} executor", name, shutdownException);
      }
    }
  }
}
