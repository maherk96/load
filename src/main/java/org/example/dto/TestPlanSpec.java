package org.example.dto;

import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class TestPlanSpec {
  private TestSpec testSpec;
  private Execution execution;

  @Data
  public static class TestSpec {
    private String id;
    private GlobalConfig globalConfig;
    private List<Scenario> scenarios;
  }

  @Data
  public static class GlobalConfig {
    private String baseUrl;
    private Map<String, String> headers;
    private Map<String, String> vars;
    private Timeouts timeouts;
  }

  @Data
  public static class Timeouts {
    private int connectionTimeoutMs;
  }

  @Data
  public static class Scenario {
    private String name;
    private List<Request> requests;
  }

  @Data
  public static class Request {
    private HttpMethod method;
    private String path;
    private Map<String, String> headers;
    private Map<String, String> query;
    private Object body;
  }

  @Data
  public static class Execution {
    private ThinkTime thinkTime;
    private LoadModel loadModel;
    private SLA globalSla;
  }

  @Data
  public static class ThinkTime {
    private ThinkTimeType type;
    private int min;
    private int max;
  }

  public enum ThinkTimeType {
    FIXED,
    RANDOM,
  }

  @Data
  public static class LoadModel {
    private WorkLoadModel type;
    private int users;
    private String rampUp;
    private String holdFor;
    private int iterations;
    private String warmup;
    private int arrivalRatePerSec;
    private int maxConcurrent;
    private String duration;
  }

  @Data
  public static class SLA {
    private double errorRatePct;
    private int p95LtMs;
    private OnError onError;

    @Data
    public static class OnError {
      private OnErrorAction action;
    }
  }

  public enum OnErrorAction {
    STOP,
    CONTINUE,
  }

  public enum WorkLoadModel {
    CLOSED,
    OPEN
  }

  public enum HttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
    PATCH
  }
}
