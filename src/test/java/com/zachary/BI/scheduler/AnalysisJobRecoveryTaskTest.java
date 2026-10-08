package com.zachary.BI.scheduler;

import com.zachary.BI.BiMq.RetryDelayPolicy;
import com.zachary.BI.config.AnalysisRecoveryProperties;
import com.zachary.BI.config.AnalysisRetryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisJobRecoveryTaskTest {

    @Test
    void validateTimeouts_shouldAcceptAiTimeoutShorterThanStaleThreshold() {
        assertDoesNotThrow(() -> task(Duration.ofMinutes(3), Duration.ofMinutes(10)).validateTimeouts());
    }

    @Test
    void validateTimeouts_shouldRejectAiTimeoutThatReachesStaleThreshold() {
        // Otherwise a worker still legitimately waiting for the provider would be reclaimed and the AI paid twice.
        assertThrows(IllegalStateException.class,
                () -> task(Duration.ofMinutes(10), Duration.ofMinutes(10)).validateTimeouts());
    }

    @Test
    void validateTimeouts_shouldAcceptDefaultRetryDelaysWithDefaultStaleThreshold() {
        // 5 min x 1.2 jitter = 6 min, below the 10 min default.
        assertDoesNotThrow(() -> task(Duration.ofMinutes(3), Duration.ofMinutes(10), new AnalysisRetryProperties())
                .validateTimeouts());
    }

    @Test
    void validateTimeouts_shouldRejectRetryDelayThatReachesStalePendingThreshold() {
        // Otherwise the recovery task would republish a job still waiting out its retry delay.
        AnalysisRetryProperties retry = new AnalysisRetryProperties();
        retry.setDelays(List.of(Duration.ofMinutes(1), Duration.ofMinutes(9)));

        assertThrows(IllegalStateException.class,
                () -> task(Duration.ofMinutes(3), Duration.ofMinutes(10), retry).validateTimeouts());
    }

    private static AnalysisJobRecoveryTask task(Duration aiReadTimeout, Duration staleRunningAfter) {
        return task(aiReadTimeout, staleRunningAfter, new AnalysisRetryProperties());
    }

    private static AnalysisJobRecoveryTask task(Duration aiReadTimeout, Duration staleRunningAfter,
                                                AnalysisRetryProperties retry) {
        AnalysisRecoveryProperties properties = new AnalysisRecoveryProperties();
        properties.setStaleRunningAfter(staleRunningAfter);
        AnalysisJobRecoveryTask task = new AnalysisJobRecoveryTask();
        ReflectionTestUtils.setField(task, "properties", properties);
        ReflectionTestUtils.setField(task, "aiReadTimeout", aiReadTimeout);
        ReflectionTestUtils.setField(task, "retryDelayPolicy", new RetryDelayPolicy(retry));
        return task;
    }
}
