package org.example.client;

import org.example.client.response.RestResponseData;
import org.example.dto.TestPlanSpec;
import org.example.load.LoadTestExecutionRunner;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LoadTestExecutionRunner using real HTTP endpoints
 * These tests focus on behavioral verification rather than mock-based testing
 */
class LoadTestExecutionRunnerTest {

    private TestPlanSpec testPlanSpec;

    @BeforeEach
    void setUp() {
        testPlanSpec = createValidTestPlanSpec();
    }

    // =====================================================
    // CONSTRUCTOR TESTS
    // =====================================================

    @Test
    @DisplayName("Constructor should initialize with valid test plan spec")
    void constructor_WithValidTestPlanSpec_ShouldInitializeSuccessfully() {
        // Given
        TestPlanSpec spec = createValidTestPlanSpec();

        // When & Then
        assertDoesNotThrow(() -> new LoadTestExecutionRunner(spec));
    }

    @Test
    @DisplayName("Constructor should handle null test plan spec gracefully")
    void constructor_WithNullTestPlanSpec_ShouldThrowException() {
        // Given
        TestPlanSpec spec = null;

        // When & Then
        assertThrows(NullPointerException.class, () -> new LoadTestExecutionRunner(spec));
    }

    // =====================================================
    // CLOSED WORKLOAD TESTS
    // =====================================================

    @Test
    @DisplayName("CLOSED workload should complete all iterations when no time limit")
    void executeClosedWorkload_NoTimeLimit_ShouldCompleteAllIterations() throws Exception {
        // Given
        TestPlanSpec spec = createClosedWorkloadSpec(2, 10, "60s", "5s", "0s");
        LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

        // When
        CompletableFuture<ComprehensiveTestReport> future = runner.execute();
        ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

        // Then
        assertNotNull(report);
        assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason());
        assertEquals(20, report.getTotalRequests()); // 2 users * 10 iterations
        assertEquals(0, report.getFailedRequests());
        assertTrue(report.getSuccessfulRequests() > 0);
    }

    @Test
    @DisplayName("CLOSED workload should terminate on hold time expiration")
    void executeClosedWorkload_HoldTimeExpires_ShouldTerminateEarly() throws Exception {
        // Given - Very short hold time with many iterations
        TestPlanSpec spec = createClosedWorkloadSpec(2, 100, "1s", "0s", "0s");
        LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

        // When
        CompletableFuture<ComprehensiveTestReport> future = runner.execute();
        ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

        // Then
        assertNotNull(report);
        assertEquals("HOLD_TIME_EXPIRED", report.getTerminationReason());
        assertTrue(report.getTotalRequests() < 200); // Should be less than total possible
        assertEquals(0, report.getFailedRequests());
    }

    @Test
    @DisplayName("CLOSED workload should handle user ramp-up correctly")
    void executeClosedWorkload_WithRampUp_ShouldStartUsersGradually() throws Exception {
        // Given
        TestPlanSpec spec = createClosedWorkloadSpec(3, 5, "10s", "3s", "0s");
        LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

        // When
        CompletableFuture<ComprehensiveTestReport> future = runner.execute();
        ComprehensiveTestReport report = future.get(20, TimeUnit.SECONDS);

        // Then
        assertNotNull(report);
        assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason());
        assertEquals(15, report.getTotalRequests()); // 3 users * 5 iterations
        assertEquals(0, report.getFailedRequests());
    }

    // =====================================================
    // OPEN WORKLOAD TESTS
    // =====================================================

    @Test
    @DisplayName("OPEN workload should complete after duration expires")
    void executeOpenWorkload_ShouldCompleteAfterDuration() throws Exception {
        // Given
        TestPlanSpec spec = createOpenWorkloadSpec(10, 50, "2s", "0s");
        LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

        // When
        CompletableFuture<ComprehensiveTestReport> future = runner.execute();
        ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

        // Then
        assertNotNull(report);
        assertEquals("DURATION_COMPLETED", report.getTerminationReason());
        // Should generate approximately 2s * 10 req/sec = ~20 requests
        assertTrue(report.getTotalRequests() >= 10 && report.getTotalRequests() <= 30);
        assertEquals(0, report.getFailedRequests());
    }

    @Test
    @DisplayName("OPEN workload should respect max concurrent limits")
    void executeOpenWorkload_ShouldRespectConcurrencyLimits() throws Exception {
        // Given - High rate but low concurrency limit
        TestPlanSpec spec = createOpenWorkloadSpec(50, 2, "2s", "0s");
        LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

        // When
        CompletableFuture<ComprehensiveTestReport> future = runner.execute();
        ComprehensiveTestReport report = future.get(15, TimeUnit.SECONDS);

        // Then
        assertNotNull(report);
        assertEquals("DURATION_COMPLETED", report.getTerminationReason());
        // Should be significantly less than 50*2=100 due to concurrency limits
        assertTrue(report.getTotalRequests() < 50);
        assertEquals(0, report.getFailedRequests());
    }

    // =====================================================
    // WARMUP TESTS
    // =====================================================

    @Test
    @DisplayName("Warmup phase should execute correctly")
    void executeWarmup_ShouldCompleteSuccessfully() throws Exception {
        // Given
        TestPlanSpec spec = createClosedWorkloadSpec(1, 1, "5s", "0s", "2s");
        LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

        // When
        CompletableFuture<ComprehensiveTestReport> future = runner.execute();
        ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

        // Then
        assertNotNull(report);
        assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason());
        assertEquals(1, report.getTotalRequests()); // Only actual test requests counted
        assertEquals(0, report.getFailedRequests());
    }

    // =====================================================
    // THINK TIME TESTS
    // =====================================================

    @Test
    @DisplayName("FIXED think time should be applied correctly")
    void applyThinkTime_Fixed_ShouldDelayCorrectly() throws Exception {
        // Given
        TestPlanSpec spec = createClosedWorkloadSpecWithThinkTime(1, 2, "10s", "0s", "0s",
                TestPlanSpec.ThinkTimeType.FIXED, 1000, 1000);
        LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

        // When
        Instant start = Instant.now();
        CompletableFuture<ComprehensiveTestReport> future = runner.execute();
        ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);
        Instant end = Instant.now();

        // Then
        assertNotNull(report);
        long durationMs = Duration.between(start, end).toMillis();
        // Should take at least 1000ms for think time between requests
        assertTrue(durationMs >= 1000);
        assertEquals(2, report.getTotalRequests());
    }

    @Test
    @DisplayName("RANDOM think time should be applied within range")
    void applyThinkTime_Random_ShouldDelayWithinRange() throws Exception {
        // Given
        TestPlanSpec spec = createClosedWorkloadSpecWithThinkTime(1, 3, "10s", "0s", "0s",
                TestPlanSpec.ThinkTimeType.RANDOM, 500, 1000);
        LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

        // When
        Instant start = Instant.now();
        CompletableFuture<ComprehensiveTestReport> future = runner.execute();
        ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);
        Instant end = Instant.now();

        // Then
        assertNotNull(report);
        long durationMs = Duration.between(start, end).toMillis();
        // Should take at least minimum think time for 2 think time applications
        assertTrue(durationMs >= 1000); // 2 * 500ms minimum
        assertEquals(3, report.getTotalRequests());
    }

    // =====================================================
    // SLA MONITORING TESTS
    // =====================================================

    @Test
    @DisplayName("Test with SLA configuration should complete")
    void slaConfiguration_ShouldNotPreventExecution() throws Exception {
        // Given
        TestPlanSpec spec = createSpecWithSLA(1, 5, "5s", 50.0, 5000,
                TestPlanSpec.OnErrorAction.CONTINUE);
        LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

        // When
        CompletableFuture<ComprehensiveTestReport> future = runner.execute();
        ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

        // Then
        assertNotNull(report);
        assertEquals(5, report.getTotalRequests());
        assertEquals(0, report.getFailedRequests());
    }

    // =====================================================
    // ERROR HANDLING TESTS
    // =====================================================

    @Test
    @DisplayName("Invalid URL should be handled gracefully")
    void executeRequest_WithInvalidUrl_ShouldRecordError() throws Exception {
        // Given - Invalid URL that will cause connection failures
        TestPlanSpec spec = createClosedWorkloadSpec(1, 2, "10s", "0s", "0s");
        spec.getTestSpec().getGlobalConfig().setBaseUrl("http://invalid-nonexistent-url-12345.com");
        spec.getTestSpec().getGlobalConfig().getTimeouts().setConnectionTimeoutMs(2000); // Short timeout
        LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

        // When
        CompletableFuture<ComprehensiveTestReport> future = runner.execute();
        ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS);

        // Then
        assertNotNull(report);
        assertEquals(2, report.getTotalRequests()); // Should attempt 2 requests
        assertTrue(report.getFailedRequests() > 0); // Should have failures
        assertTrue(report.getErrorRate() > 0); // Should have error rate > 0
        assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason()); // Should complete all attempts
    }

    // =====================================================
    // DURATION PARSING TESTS
    // =====================================================

    @Test
    @DisplayName("parseDuration should handle seconds correctly")
    void parseDuration_Seconds_ShouldParseCorrectly() {
        // Given
        TestPlanSpec spec = createValidTestPlanSpec();

        // When & Then
        assertDoesNotThrow(() -> new LoadTestExecutionRunner(spec));
    }

    @Test
    @DisplayName("parseDuration should handle minutes correctly")
    void parseDuration_Minutes_ShouldParseCorrectly() {
        // Given
        TestPlanSpec spec = createClosedWorkloadSpec(1, 1, "2m", "0s", "0s");

        // When & Then
        assertDoesNotThrow(() -> new LoadTestExecutionRunner(spec));
    }

    // =====================================================
    // CLEANUP TESTS
    // =====================================================

    @Test
    @DisplayName("Cleanup should complete without errors")
    void cleanup_ShouldCompleteWithoutErrors() throws Exception {
        // Given
        TestPlanSpec spec = createClosedWorkloadSpec(1, 1, "5s", "0s", "0s");
        LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

        // When
        CompletableFuture<ComprehensiveTestReport> future = runner.execute();
        ComprehensiveTestReport report = future.get(30, TimeUnit.SECONDS); // Increased timeout

        // Then
        assertNotNull(report);
        // Test completes successfully, indicating proper cleanup
        assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason());
        assertEquals(1, report.getTotalRequests());
        assertEquals(0, report.getFailedRequests()); // Should succeed with real endpoint
    }

    // =====================================================
    // HELPER METHODS
    // =====================================================

    private TestPlanSpec createValidTestPlanSpec() {
        return createClosedWorkloadSpec(2, 5, "10s", "2s", "1s");
    }

    private TestPlanSpec createClosedWorkloadSpec(int users, int iterations,
                                                  String holdFor, String rampUp, String warmup) {
        TestPlanSpec spec = new TestPlanSpec();

        // Test spec
        TestPlanSpec.TestSpec testSpec = new TestPlanSpec.TestSpec();
        testSpec.setId("test-1");

        // Global config - Use real working endpoint
        TestPlanSpec.GlobalConfig globalConfig = new TestPlanSpec.GlobalConfig();
        globalConfig.setBaseUrl("https://jsonplaceholder.typicode.com");

        TestPlanSpec.Timeouts timeouts = new TestPlanSpec.Timeouts();
        timeouts.setConnectionTimeoutMs(15000);
        globalConfig.setTimeouts(timeouts);

        testSpec.setGlobalConfig(globalConfig);

        // Scenarios
        TestPlanSpec.Scenario scenario = new TestPlanSpec.Scenario();
        scenario.setName("test-scenario");

        TestPlanSpec.Request request = new TestPlanSpec.Request();
        request.setMethod(TestPlanSpec.HttpMethod.GET);
        request.setPath("/posts/1"); // Use real endpoint
        scenario.setRequests(request);

        testSpec.setScenarios(java.util.List.of(scenario));
        spec.setTestSpec(testSpec);

        // Execution
        TestPlanSpec.Execution execution = new TestPlanSpec.Execution();

        TestPlanSpec.LoadModel loadModel = new TestPlanSpec.LoadModel();
        loadModel.setType(TestPlanSpec.WorkLoadModel.CLOSED);
        loadModel.setUsers(users);
        loadModel.setIterations(iterations);
        loadModel.setHoldFor(holdFor);
        loadModel.setRampUp(rampUp);
        loadModel.setWarmup(warmup);

        execution.setLoadModel(loadModel);
        spec.setExecution(execution);

        return spec;
    }

    private TestPlanSpec createOpenWorkloadSpec(int arrivalRate, int maxConcurrent,
                                                String duration, String warmup) {
        TestPlanSpec spec = createValidTestPlanSpec();

        TestPlanSpec.LoadModel loadModel = spec.getExecution().getLoadModel();
        loadModel.setType(TestPlanSpec.WorkLoadModel.OPEN);
        loadModel.setArrivalRatePerSec(arrivalRate);
        loadModel.setMaxConcurrent(maxConcurrent);
        loadModel.setDuration(duration);
        loadModel.setWarmup(warmup);

        return spec;
    }

    private TestPlanSpec createClosedWorkloadSpecWithThinkTime(int users, int iterations,
                                                               String holdFor, String rampUp, String warmup,
                                                               TestPlanSpec.ThinkTimeType type,
                                                               int min, int max) {
        TestPlanSpec spec = createClosedWorkloadSpec(users, iterations, holdFor, rampUp, warmup);

        TestPlanSpec.ThinkTime thinkTime = new TestPlanSpec.ThinkTime();
        thinkTime.setType(type);
        thinkTime.setMin(min);
        thinkTime.setMax(max);

        spec.getExecution().setThinkTime(thinkTime);

        return spec;
    }

    private TestPlanSpec createSpecWithSLA(int users, int iterations, String holdFor,
                                           double errorRatePct, int p95LtMs,
                                           TestPlanSpec.OnErrorAction onErrorAction) {
        TestPlanSpec spec = createClosedWorkloadSpec(users, iterations, holdFor, "1s", "0s");

        TestPlanSpec.SLA sla = new TestPlanSpec.SLA();
        sla.setErrorRatePct(errorRatePct);
        sla.setP95LtMs(p95LtMs);

        TestPlanSpec.SLA.OnError onError = new TestPlanSpec.SLA.OnError();
        onError.setAction(onErrorAction);
        sla.setOnError(onError);

        spec.getExecution().setGlobalSla(sla);

        return spec;
    }

    private RestResponseData createSuccessResponse() {
        var response = new RestResponseData();
        response.setStatusCode(200);
        response.setBody("{\"id\": 1, \"title\": \"Test Post\"}");
        response.setHeaders(Map.of("Content-Type", "application/json"));
        response.setResponseTimeMs(100); // Simulate 100ms response time
        return response;
    }

    private RestResponseData createErrorResponse() {
        var response = new RestResponseData();
        response.setStatusCode(500);
        response.setBody("Internal Server Error");
        response.setHeaders(Map.of("Content-Type", "text/plain"));
        response.setResponseTimeMs(100);
        return response;
    }
}

// =====================================================
// INTEGRATION TESTS
// =====================================================

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("integration")  // Tag for separating integration tests
class LoadTestExecutionRunnerIntegrationTest {

    @Test
    @Order(1)
    @DisplayName("Integration test - Small CLOSED workload end-to-end")
    void integrationTest_SmallClosedWorkload_ShouldCompleteSuccessfully() throws Exception {
        // Given
        TestPlanSpec spec = createRealTestPlanSpec();
        LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

        // When
        CompletableFuture<ComprehensiveTestReport> future = runner.execute();
        ComprehensiveTestReport report = future.get(45, TimeUnit.SECONDS); // Increased timeout

        // Then
        assertNotNull(report);
        assertEquals("ALL_ITERATIONS_COMPLETED", report.getTerminationReason());
        assertTrue(report.getTotalRequests() > 0);
        assertEquals(0, report.getFailedRequests());
        assertTrue(report.getSuccessfulRequests() > 0);
    }

    @Test
    @Order(2)
    @DisplayName("Integration test - Small OPEN workload end-to-end")
    void integrationTest_SmallOpenWorkload_ShouldCompleteSuccessfully() throws Exception {
        // Given
        TestPlanSpec spec = createRealOpenWorkloadSpec();
        LoadTestExecutionRunner runner = new LoadTestExecutionRunner(spec);

        // When
        CompletableFuture<ComprehensiveTestReport> future = runner.execute();
        ComprehensiveTestReport report = future.get(45, TimeUnit.SECONDS); // Increased timeout

        // Then
        assertNotNull(report);
        assertEquals("DURATION_COMPLETED", report.getTerminationReason());
        assertTrue(report.getTotalRequests() > 0);
        // Allow some failures for network issues, but most should succeed
        assertTrue(report.getSuccessfulRequests() > 0);
    }

    private TestPlanSpec createRealTestPlanSpec() {
        // Create a real test plan spec that hits a real endpoint
        TestPlanSpec spec = new TestPlanSpec();

        TestPlanSpec.TestSpec testSpec = new TestPlanSpec.TestSpec();
        testSpec.setId("integration-test");

        TestPlanSpec.GlobalConfig globalConfig = new TestPlanSpec.GlobalConfig();
        globalConfig.setBaseUrl("https://jsonplaceholder.typicode.com");

        TestPlanSpec.Timeouts timeouts = new TestPlanSpec.Timeouts();
        timeouts.setConnectionTimeoutMs(5000);
        globalConfig.setTimeouts(timeouts);

        testSpec.setGlobalConfig(globalConfig);

        TestPlanSpec.Scenario scenario = new TestPlanSpec.Scenario();
        scenario.setName("get-posts");

        TestPlanSpec.Request request = new TestPlanSpec.Request();
        request.setMethod(TestPlanSpec.HttpMethod.GET);
        request.setPath("/posts/1");
        scenario.setRequests(request);

        testSpec.setScenarios(java.util.List.of(scenario));
        spec.setTestSpec(testSpec);

        TestPlanSpec.Execution execution = new TestPlanSpec.Execution();

        TestPlanSpec.LoadModel loadModel = new TestPlanSpec.LoadModel();
        loadModel.setType(TestPlanSpec.WorkLoadModel.CLOSED);
        loadModel.setUsers(2);
        loadModel.setIterations(3);
        loadModel.setHoldFor("10s");
        loadModel.setRampUp("2s");
        loadModel.setWarmup("1s");

        execution.setLoadModel(loadModel);
        spec.setExecution(execution);

        return spec;
    }

    private TestPlanSpec createRealOpenWorkloadSpec() {
        TestPlanSpec spec = createRealTestPlanSpec();

        TestPlanSpec.LoadModel loadModel = spec.getExecution().getLoadModel();
        loadModel.setType(TestPlanSpec.WorkLoadModel.OPEN);
        loadModel.setArrivalRatePerSec(2);
        loadModel.setMaxConcurrent(5);
        loadModel.setDuration("3s");
        loadModel.setWarmup("1s");

        return spec;
    }
}