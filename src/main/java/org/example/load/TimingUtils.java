package org.example.load;

import org.example.dto.TestPlanSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class TimingUtils {

  private static final Logger log = LoggerFactory.getLogger(TimingUtils.class);

  public Duration parseDuration(String duration) {
    if (duration == null || duration.trim().isEmpty()) {
      return Duration.ZERO;
    }

    var trimmed = duration.trim().toLowerCase();
    if (trimmed.endsWith("s")) {
      return Duration.ofSeconds(Integer.parseInt(trimmed.substring(0, trimmed.length() - 1)));
    } else if (trimmed.endsWith("m")) {
      return Duration.ofMinutes(Integer.parseInt(trimmed.substring(0, trimmed.length() - 1)));
    } else {
      return Duration.ofSeconds(Integer.parseInt(trimmed));
    }
  }

  public void applyThinkTime(TestPlanSpec.ThinkTime thinkTime, AtomicBoolean cancelled) {
    if (thinkTime == null || cancelled.get()) return;

    try {
      int delay;
      if (thinkTime.getType() == TestPlanSpec.ThinkTimeType.FIXED) {
        delay = thinkTime.getMin();
      } else {
        delay = ThreadLocalRandom.current().nextInt(thinkTime.getMin(), thinkTime.getMax() + 1);
      }

      if (delay > 0 && !cancelled.get()) {
        log.debug("Applying think time: {} ms", delay);
        Thread.sleep(delay);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public void sleep(Duration duration, AtomicBoolean cancelled) {
    if (cancelled.get()) return;

    try {
      long totalMillis = duration.toMillis();
      long sleptMillis = 0;
      long checkInterval = Math.min(100, totalMillis); // Check cancellation every 100ms or total duration

      while (sleptMillis < totalMillis && !cancelled.get()) {
        long remainingMillis = totalMillis - sleptMillis;
        long sleepTime = Math.min(checkInterval, remainingMillis);

        Thread.sleep(sleepTime);
        sleptMillis += sleepTime;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public void sleep(Duration duration) {
    sleep(duration, new AtomicBoolean(false)); // Fallback for non-cancellable sleeps
  }
}