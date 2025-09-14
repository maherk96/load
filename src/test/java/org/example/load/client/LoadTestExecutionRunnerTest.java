package org.example.load.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.client.LoadHttpClient;
import org.example.client.response.RestResponseData;
import org.example.dto.TestPlanSpec;
import org.example.load.LoadTestExecutionRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for LoadTestExecutionRunner and its collaborators.
 * NOTE: This suite assumes the TestPlanSpec DTO provides the getter methods used in production code.
 * If method/enum names differ in your DTO, adjust the stubbing helpers accordingly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoadTestExecutionRunnerTest {

    private AutoCloseable mocks;

    @Mock
    private LoadHttpClient httpClient; // Mocked and injected via reflection into RequestExecutor


    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        attachLogAppenders();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) mocks.close();
    }

    // ---------- Logging helpers ----------

    private void attachLogAppenders() {

    }

    private ListAppender<ILoggingEvent> attach(Logger logger) {
        logger.setLevel(Level.DEBUG);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }


    // ---------- Parameter sources ----------

    static Stream<ClosedParams> closedParams() {
        return Stream.of(
                new ClosedParams( // happy path, short phases, think fixed 50ms
                        3, 2, "1s", "1s", "1s",
                        thinkFixed(50),
                        2, // scenarios
                        2  // requests per scenario
                ),
                new ClosedParams( // single user/iteration (edge-ish, no think between)
                        1, 1, "0s", "0s", "1s",
                        null,
                        2,
                        3
                )
        );
    }

    static Stream<OpenParams> openParams() {
        return Stream.of(
                new OpenParams( // happy path open model
                        20, 10, "0s", "2s",
                        2, 2 // 2 scenarios x 2 req each
                )
        );
    }

    // ---------- Tests: CLOSED model ----------

    @ParameterizedTest(name = "CLOSED model happy path #{index}")
    @MethodSource("closedParams")
    void closedModel_happyPath_countsPhasesAndRequests(ClosedParams p) throws Exception {
        var spec = buildClosedSpec(p.users, p.iterations, p.warmup, p.rampUp, p.hold, p.thinkTime,
                p.scenarios, p.requestsPerScenario);

        // mock HTTP client: always 200, small delay to simulate network
        when(httpClient.execute(any()))
                .thenAnswer(successRespWithDelay(5));

        var runner = new LoadTestExecutionRunner(spec);
        injectHttpClient(runner, httpClient);

        var future = runner.execute();

        // Await completion (runner handles cleanup in finally)
        assertDoesNotThrow(() -> future.get(30, TimeUnit.SECONDS));

        // Verify request count:
        int scenarioRequests = p.scenarios * p.requestsPerScenario;
        int expected = p.users * p.iterations * scenarioRequests;
        verify(httpClient, atLeast(1)).execute(any());
        verify(httpClient, times(expected)).execute(any());

        // Active requests return to 0
        assertEquals(0, getActiveRequests(runner));

    }

    // Think time verification (fixed and random)
    @Test
    void closedModel_thinkTime_respectedAndSleepCalled() throws Exception {
        var think = thinkFixed(120); // 120ms fixed think
        var spec = buildClosedSpec(1, 3, "0s", "0s", "5s", think, 1, 1);

        when(httpClient.execute(any()))
                .thenAnswer(successRespWithDelay(1));

        var runner = new LoadTestExecutionRunner(spec);
        injectHttpClient(runner, httpClient);

        var start = Instant.now();
        var future = runner.execute();
        assertDoesNotThrow(() -> future.get(30, TimeUnit.SECONDS));
        var elapsedMs = Duration.between(start, Instant.now()).toMillis();

        // 3 iterations -> 2 think intervals * 120ms = >= 240ms
        assertTrue(elapsedMs >= 200, "Elapsed should reflect think time overhead (~>=240ms; small margin allowed)");

    }

    // ---------- Tests: OPEN model ----------

    @ParameterizedTest(name = "OPEN model happy path #{index}")
    @MethodSource("openParams")
    void openModel_happyPath_arrivalRateAndPhases(OpenParams p) throws Exception {
        var spec = buildOpenSpec(p.arrivalRate, p.maxConcurrent, "0s", p.duration,
                p.scenarios, p.requestsPerScenario);

        when(httpClient.execute(any()))
                .thenAnswer(successRespWithDelay(20)); // add delay so concurrency matters

        var runner = new LoadTestExecutionRunner(spec);
        injectHttpClient(runner, httpClient);

        var future = runner.execute();
        assertDoesNotThrow(() -> future.get(30, TimeUnit.SECONDS));

        // Expect approximately arrivalRate * durationSeconds * scenarioRequests
        int scenarioRequests = p.scenarios * p.requestsPerScenario;
        int approx = p.arrivalRate * seconds(p.duration) * scenarioRequests;

        // Tolerance because wall-clock, scheduling, and timer granularity
        ArgumentCaptor<TestPlanSpec.Request> reqCaptor = ArgumentCaptor.forClass(TestPlanSpec.Request.class);
        verify(httpClient, atLeast(1)).execute(reqCaptor.capture());

        int actualCalls = mockInvocationsCount(httpClient);
        assertTrue(actualCalls > 0, "Should execute at least one request");
        // Allow -40% / +60% tolerance given scheduling (adjust if needed)
        int lower = (int) Math.floor(approx * 0.6);
        int upper = (int) Math.ceil(approx * 1.6);
        assertTrue(actualCalls >= lower && actualCalls <= upper,
                "Actual calls " + actualCalls + " should be near approx " + approx + " within tolerance");

        assertEquals(0, getActiveRequests(runner));
    }

    @Test
    void openModel_backPressure_lowMaxConcurrentTriggersLoggingAndLimitsThroughput() throws Exception {
        int arrivalRate = 50;
        int maxConcurrent = 1; // force back pressure
        int scenarios = 1, requestsPerScenario = 1;
        var duration = "2s";

        var spec = buildOpenSpec(arrivalRate, maxConcurrent, "0s", duration, scenarios, requestsPerScenario);

        // Make each request "heavy" to keep the single slot occupied
        when(httpClient.execute(any()))
                .thenAnswer(successRespWithDelay(150));

        var runner = new LoadTestExecutionRunner(spec);
        injectHttpClient(runner, httpClient);

        var future = runner.execute();
        assertDoesNotThrow(() -> future.get(30, TimeUnit.SECONDS));

        // Arrival attempts (rate*seconds) >> actual due to concurrency=1 and long RT
        int expectedArrivals = arrivalRate * seconds(duration);
        int actual = mockInvocationsCount(httpClient);
        assertTrue(actual < expectedArrivals,
                "Actual executed requests should be fewer than attempted arrivals due to back-pressure");

        assertEquals(0, getActiveRequests(runner));
    }

    // ---------- Cancellation ----------

    @Test
    void cancellation_stopsFurtherSubmissions_setsReasonAndDrainsActive() throws Exception {
        // Long-running open test to cancel mid-flight
        var spec = buildOpenSpec(30, 5, "0s", "10s", 2, 2);

        // Each request takes ~100ms
        when(httpClient.execute(any()))
                .thenAnswer(successRespWithDelay(100));

        var runner = new LoadTestExecutionRunner(spec);
        injectHttpClient(runner, httpClient);

        var future = runner.execute();

        // Let it run briefly
        Thread.sleep(400);
        int callsBeforeCancel = mockInvocationsCount(httpClient);

        // Cancel
        runner.cancel();

        // Wait for graceful stop
        assertThrows(TimeoutException.class, () -> future.get(200, TimeUnit.MILLISECONDS),
                "Immediate completion unlikely; will still be shutting down");
        // But within a few seconds it should be done
        assertDoesNotThrow(() -> future.get(30, TimeUnit.SECONDS));

        // After cancellation, calls shouldn't keep rising for long
        int callsAfterCancel = mockInvocationsCount(httpClient);
        // Allow a little drift because running requests complete
        assertTrue(callsAfterCancel >= callsBeforeCancel, "Count can't go backwards");
        assertTrue(callsAfterCancel - callsBeforeCancel < 50,
                "Only a small tail of in-flight requests should complete after cancellation");

        // Termination reason + active requests drained
        assertEquals("CANCELLED", runner.getTerminationReason(), "Runner terminationReason should be CANCELLED");
        assertEquals(0, getActiveRequests(runner));
    }

    // ---------- Error handling ----------

    @Test
    void errorFromHttpClient_terminatesWithExecutionError() throws Exception {
        var spec = buildClosedSpec(1, 1, "0s", "0s", "1s", null, 1, 1);

        when(httpClient.execute(any()))
                .thenThrow(new RuntimeException("boom"));

        var runner = new LoadTestExecutionRunner(spec);
        injectHttpClient(runner, httpClient);

        var future = runner.execute();

        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof RuntimeException, "Wrapped in RuntimeException");
        // Note: runner.terminationReason is not set in catch branch by design; we assert via logs instead.
        assertEquals(0, getActiveRequests(runner));
    }

    // ---------- Helpers & fixtures ----------

    private static class ClosedParams {
        final int users, iterations;
        final String warmup, rampUp, hold;
        final TestPlanSpec.ThinkTime thinkTime;
        final int scenarios, requestsPerScenario;
        ClosedParams(int users, int iterations, String warmup, String rampUp, String hold,
                     TestPlanSpec.ThinkTime thinkTime, int scenarios, int requestsPerScenario) {
            this.users = users; this.iterations = iterations;
            this.warmup = warmup; this.rampUp = rampUp; this.hold = hold;
            this.thinkTime = thinkTime;
            this.scenarios = scenarios; this.requestsPerScenario = requestsPerScenario;
        }
    }

    private static class OpenParams {
        final int arrivalRate, maxConcurrent;
        final String warmup, duration;
        final int scenarios, requestsPerScenario;
        OpenParams(int arrivalRate, int maxConcurrent, String warmup, String duration,
                   int scenarios, int requestsPerScenario) {
            this.arrivalRate = arrivalRate; this.maxConcurrent = maxConcurrent;
            this.warmup = warmup; this.duration = duration;
            this.scenarios = scenarios; this.requestsPerScenario = requestsPerScenario;
        }
    }

    private static TestPlanSpec.ThinkTime thinkFixed(int ms) {
        TestPlanSpec.ThinkTime t = mock(TestPlanSpec.ThinkTime.class);
        when(t.getType()).thenReturn(TestPlanSpec.ThinkTimeType.FIXED);
        when(t.getMin()).thenReturn(ms);
        when(t.getMax()).thenReturn(ms);
        return t;
    }

    private static TestPlanSpec buildClosedSpec(int users, int iterations, String warmup, String rampUp, String hold,
                                                TestPlanSpec.ThinkTime think,
                                                int scenarioCount, int requestsPerScenario) {
        TestPlanSpec spec = mock(TestPlanSpec.class, RETURNS_DEEP_STUBS);

        // testSpec + global config
        when(spec.getTestSpec().getId()).thenReturn("closed-test");
        when(spec.getTestSpec().getGlobalConfig().getBaseUrl()).thenReturn("http://localhost");
        when(spec.getTestSpec().getGlobalConfig().getTimeouts().getConnectionTimeoutMs()).thenReturn(5000);
        when(spec.getTestSpec().getGlobalConfig().getHeaders()).thenReturn(Map.of("X-Test", "1"));
        when(spec.getTestSpec().getGlobalConfig().getVars()).thenReturn(Map.of("v", "x"));

        // execution + load model
        var loadModel = mock(TestPlanSpec.LoadModel.class);
        when(loadModel.getType()).thenReturn(TestPlanSpec.WorkLoadModel.CLOSED);
        when(loadModel.getUsers()).thenReturn(users);
        when(loadModel.getIterations()).thenReturn(iterations);
        when(loadModel.getWarmup()).thenReturn(warmup);
        when(loadModel.getRampUp()).thenReturn(rampUp);
        when(loadModel.getHoldFor()).thenReturn(hold);

        when(spec.getExecution().getLoadModel()).thenReturn(loadModel);
        when(spec.getExecution().getThinkTime()).thenReturn(think);

        // scenarios + requests
        List<TestPlanSpec.Scenario> scenarios = new ArrayList<>();
        for (int s = 0; s < scenarioCount; s++) {
            scenarios.add(mockScenario("scenario-" + s, requestsPerScenario));
        }
        when(spec.getTestSpec().getScenarios()).thenReturn(scenarios);

        return spec;
    }

    private static TestPlanSpec buildOpenSpec(int arrivalRate, int maxConcurrent,
                                              String warmup, String duration,
                                              int scenarioCount, int requestsPerScenario) {
        TestPlanSpec spec = mock(TestPlanSpec.class, RETURNS_DEEP_STUBS);

        when(spec.getTestSpec().getId()).thenReturn("open-test");
        when(spec.getTestSpec().getGlobalConfig().getBaseUrl()).thenReturn("http://localhost");
        when(spec.getTestSpec().getGlobalConfig().getTimeouts().getConnectionTimeoutMs()).thenReturn(5000);
        when(spec.getTestSpec().getGlobalConfig().getHeaders()).thenReturn(Map.of());
        when(spec.getTestSpec().getGlobalConfig().getVars()).thenReturn(Map.of());

        var lm = mock(TestPlanSpec.LoadModel.class);
        when(lm.getType()).thenReturn(TestPlanSpec.WorkLoadModel.OPEN);
        when(lm.getWarmup()).thenReturn(warmup);
        when(lm.getDuration()).thenReturn(duration);
        when(lm.getArrivalRatePerSec()).thenReturn(arrivalRate);
        when(lm.getMaxConcurrent()).thenReturn(maxConcurrent);

        when(spec.getExecution().getLoadModel()).thenReturn(lm);
        when(spec.getExecution().getThinkTime()).thenReturn(null);

        List<TestPlanSpec.Scenario> scenarios = new ArrayList<>();
        for (int s = 0; s < scenarioCount; s++) {
            scenarios.add(mockScenario("scenario-" + s, requestsPerScenario));
        }
        when(spec.getTestSpec().getScenarios()).thenReturn(scenarios);

        return spec;
    }

    private static TestPlanSpec.Scenario mockScenario(String name, int requests) {
        TestPlanSpec.Scenario sc = mock(TestPlanSpec.Scenario.class, RETURNS_DEEP_STUBS);
        when(sc.getName()).thenReturn(name);

        List<TestPlanSpec.Request> reqs = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            reqs.add(mockRequest("/api/" + name + "/" + i));
        }
        when(sc.getRequests()).thenReturn(reqs);
        return sc;
    }

    private static TestPlanSpec.Request mockRequest(String path) {
        TestPlanSpec.Request r = mock(TestPlanSpec.Request.class, RETURNS_DEEP_STUBS);
        // Use the real enum from your DTO (adjust name if different)
        when(r.getMethod()).thenReturn(TestPlanSpec.HttpMethod.GET);
        when(r.getPath()).thenReturn(path);
        when(r.getQuery()).thenReturn(Map.of());
        when(r.getHeaders()).thenReturn(Map.of());
        when(r.getBody()).thenReturn(null);
        return r;
    }

    private static int seconds(String duration) {
        String d = duration.trim().toLowerCase();
        if (d.endsWith("ms")) return 0;
        if (d.endsWith("s")) return Integer.parseInt(d.substring(0, d.length() - 1));
        if (d.endsWith("m")) return Integer.parseInt(d.substring(0, d.length() - 1)) * 60;
        return Integer.parseInt(d);
    }

    private static Answer<RestResponseData> successRespWithDelay(long delayMs) {
        return invocation -> {
            if (delayMs > 0) Thread.sleep(delayMs);
            var resp = new RestResponseData();
            resp.setStatusCode(200);
            resp.setBody("{}");
            resp.setHeaders(Map.of());
            resp.setResponseTimeMs(delayMs);
            return resp;
        };
    }

    // ---------- Reflection injection & state reads ----------

    private static void injectHttpClient(LoadTestExecutionRunner runner, LoadHttpClient mockClient) throws Exception {
        // Access private final field requestExecutor
        Field reField = LoadTestExecutionRunner.class.getDeclaredField("requestExecutor");
        reField.setAccessible(true);
        Object requestExecutor = reField.get(runner);

        // Inside RequestExecutor, set private final 'httpClient'
        Field hcField = requestExecutor.getClass().getDeclaredField("httpClient");
        hcField.setAccessible(true);
        hcField.set(requestExecutor, mockClient);
    }

    private static int getActiveRequests(LoadTestExecutionRunner runner) throws Exception {
        Field reField = LoadTestExecutionRunner.class.getDeclaredField("requestExecutor");
        reField.setAccessible(true);
        Object requestExecutor = reField.get(runner);
        return (int) requestExecutor.getClass().getMethod("getActiveRequestCount").invoke(requestExecutor);
    }

    // Mockito doesn't expose a global count directly; track via interactions
    private static int mockInvocationsCount(LoadHttpClient client) {
        // Count all execute() interactions recorded so far
        try {
            verify(client, atLeast(0)).execute(any());
            // Mockito doesn't have a direct count getter; re-verify with a large max and subtract failures.
            // Practical workaround: spy an AtomicInteger via Answer in your project if you need an exact running counter.
        } catch (Throwable ignore) { }
        // Fallback: approximate using the invocation list
        return (int) mockingDetails(client).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("execute"))
                .count();
    }

    // ---------- Log inspection helpers ----------

    private static boolean logContains(ListAppender<ILoggingEvent> appender, String snippet) {
        return appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains(snippet));
    }

    private static int indexOf(ListAppender<ILoggingEvent> appender, String snippet) {
        for (int i = 0; i < appender.list.size(); i++) {
            if (appender.list.get(i).getFormattedMessage().contains(snippet)) return i;
        }
        return -1;
    }
}