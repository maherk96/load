package org.example.client;

public class SLAViolation {
  private final String reason;

  public SLAViolation(String reason) {
    this.reason = reason;
  }

  public String getReason() {
    return reason;
  }
}
