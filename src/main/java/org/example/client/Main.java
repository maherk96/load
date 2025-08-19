package org.example.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.concurrent.CompletableFuture;
import org.example.dto.TestPlanSpec;
import org.example.load.LoadTestExecutionRunner;
import org.example.util.JsonUtil;

public class Main {
  public static void main(String[] args) throws JsonProcessingException {
    var temp =
        """
                {
                  "testSpec": {
                    "id": "jsonplaceholder-mixed-api-test",
                    "globalConfig": {
                      "baseUrl": "https://jsonplaceholder.typicode.com",
                      "headers": {
                        "Content-Type": "application/json; charset=UTF-8",
                        "User-Agent": "LoadTester-Open/1.0",
                        "Accept": "application/json"
                      },
                      "vars": {
                        "userId": "42",
                        "postId": "1",
                        "testRunId": "run-001"
                      },
                      "timeouts": {
                        "connectionTimeoutMs": 15000
                      }
                    },
                    "scenarios": [
                      {
                        "name": "create-blog-post",
                        "requests": {
                          "method": "POST",
                          "path": "/posts",
                          "headers": {
                            "X-Test-Run": "{{testRunId}}"
                          },
                          "query": {
                            "source": "load-test"
                          },
                          "body": {
                            "title": "Performance Test Blog Post #{{testRunId}}",
                            "body": "This post was created during load testing to simulate real user behavior. It contains meaningful content to test the API's response to realistic payloads. The test run ID is {{testRunId}} and user ID is {{userId}}.",
                            "userId": "{{userId}}"
                          }
                        }
                      }
                    ]
                  },
                  "execution": {
                    "thinkTime": {
                      "type": "RANDOM",
                      "min": 5,
                      "max": 5,
                    },
                    "loadModel": {
                      "type": "OPEN",
                      "arrivalRatePerSec": 20,
                      "maxConcurrent": 50,
                      "duration": "30s",
                      "warmup": "5s"
                    },
                    "globalSla": {
                      "errorRatePct": 2.0,
                      "p95LtMs": 1000,
                      "onError": {
                        "action": "STOP"
                      }
                    }
                  }
                }
                """;
    TestPlanSpec read = JsonUtil.read(temp, TestPlanSpec.class);
      TestPlanSpec.LoadModel loadModel = read.getExecution().getLoadModel();
      loadModel.setType(TestPlanSpec.WorkLoadModel.CLOSED);
      loadModel.setIterations(50);
      loadModel.setHoldFor("1m");
      loadModel.setUsers(50);
      loadModel.setRampUp("30s");
      loadModel.setWarmup("5s");
      LoadTestExecutionRunner runner = new LoadTestExecutionRunner(read);
    CompletableFuture<ComprehensiveTestReport> execute = runner.execute();
    execute
        .thenAccept(
            report -> {
              System.out.println("Test completed successfully!");
              try {
                System.out.println("Report: " + JsonUtil.toJson(report));
              } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
              }
            })
        .exceptionally(
            throwable -> {
              System.err.println("Test execution failed: " + throwable.getMessage());
              return null;
            });
}


}
