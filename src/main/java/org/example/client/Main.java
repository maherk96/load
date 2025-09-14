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
                                "requests": [
                                  {
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
                                ]
                              },
                              {
                                "name": "get-posts",
                                "requests": [
                                  {
                                    "method": "GET",
                                    "path": "/posts",
                                    "headers": {
                                      "X-Test-Run": "{{testRunId}}"
                                    },
                                    "query": {
                                      "userId": "{{userId}}"
                                    }
                                  },
                                  {
                                    "method": "GET",
                                    "path": "/posts/{{postId}}",
                                    "headers": {
                                      "X-Test-Run": "{{testRunId}}"
                                    }
                                  }
                                ]
                              }
                            ]
                          },
                          "execution": {
                            "thinkTime": {
                              "type": "RANDOM",
                              "min": 1000,
                              "max": 3000
                            },
                            "loadModel": {
                              "type": "OPEN",
                              "arrivalRatePerSec": 20,
                              "maxConcurrent": 50,
                              "duration": "30s",
                              "warmup": "5s"
                            }
                          }
                        }
                        """;

        TestPlanSpec testPlan = JsonUtil.read(temp, TestPlanSpec.class);

        // Configure for CLOSED workload model
        TestPlanSpec.LoadModel loadModel = testPlan.getExecution().getLoadModel();


        LoadTestExecutionRunner runner = new LoadTestExecutionRunner(testPlan);

        System.out.println("Starting load test execution...");
        System.out.println("Configuration:");
        System.out.println("  - Workload: " + loadModel.getType());
        System.out.println("  - Users: " + loadModel.getUsers());
        System.out.println("  - Iterations per user: " + loadModel.getIterations());
        System.out.println("  - Ramp-up: " + loadModel.getRampUp());
        System.out.println("  - Hold time: " + loadModel.getHoldFor());
        System.out.println("  - Warmup: " + loadModel.getWarmup());
        System.out.println("  - Scenarios: " + testPlan.getTestSpec().getScenarios().size());

        // Calculate total expected requests
        int totalRequests = 0;
        for (var scenario : testPlan.getTestSpec().getScenarios()) {
            totalRequests += scenario.getRequests().size();
        }
        int expectedTotalRequests = loadModel.getUsers() * loadModel.getIterations() * totalRequests;
        System.out.println("  - Expected total requests: " + expectedTotalRequests);
        System.out.println();

        CompletableFuture<Void> execution = runner.execute();

        execution
                .thenRun(() -> {
                    System.out.println("=================================");
                    System.out.println("Test completed successfully!");
                    System.out.println("=================================");
                    System.out.println("Check the logs above for detailed request execution information.");
                })
                .exceptionally(throwable -> {
                    System.err.println("=================================");
                    System.err.println("Test execution failed!");
                    System.err.println("=================================");
                    System.err.println("Error: " + throwable.getMessage());
                    throwable.printStackTrace();
                    return null;
                });

        // Wait for completion
        try {
            execution.get();
        } catch (Exception e) {
            System.err.println("Failed to wait for test completion: " + e.getMessage());
        }

        // Ensure proper cleanup
        runner.cleanup();
        System.out.println("Main method completed.");
    }
}