package com.zachary.BI.scheduler;

import com.zachary.BI.config.AnalysisRecoveryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

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

    private static AnalysisJobRecoveryTask task(Duration aiReadTimeout, Duration staleRunningAfter) {
        AnalysisRecoveryProperties properties = new AnalysisRecoveryProperties();
        properties.setStaleRunningAfter(staleRunningAfter);
        AnalysisJobRecoveryTask task = new AnalysisJobRecoveryTask();
        ReflectionTestUtils.setField(task, "properties", properties);
        ReflectionTestUtils.setField(task, "aiReadTimeout", aiReadTimeout);
        return task;
    }
}
