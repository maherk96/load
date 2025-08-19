package org.example.client;

import org.example.client.metrics.TestMetrics;
import org.example.dto.TestPlanSpec;

// Simple SLA Monitor implementation
public class SLAMonitor {
  private final TestPlanSpec.SLA sla;
  private final TestMetrics metrics;

  public SLAMonitor(TestPlanSpec.SLA sla, TestMetrics metrics) {
    this.sla = sla;
    this.metrics = metrics;
  }

  public SLAViolation checkSLA() {
    if (sla == null) return null;

    // Check error rate
    if (sla.getErrorRatePct() > 0) {
      double currentErrorRate = metrics.getErrorRate();
      if (currentErrorRate > sla.getErrorRatePct()) {
        return new SLAViolation(
            "Error rate " + currentErrorRate + "% exceeds limit " + sla.getErrorRatePct() + "%");
      }
    }

    // Check P95 response time
    if (sla.getP95LtMs() > 0) {
      long currentP95 = metrics.getP95ResponseTime();
      if (currentP95 > sla.getP95LtMs()) {
        return new SLAViolation(
            "P95 response time " + currentP95 + "ms exceeds limit " + sla.getP95LtMs() + "ms");
      }
    }

    return null;
  }
}
