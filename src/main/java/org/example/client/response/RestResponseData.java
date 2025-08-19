package org.example.client.response;

import java.util.Map;
import lombok.Data;

@Data
public class RestResponseData {
  private int statusCode;
  private Map<String, String> headers;
  private String body;
  private long responseTimeMs;
}
