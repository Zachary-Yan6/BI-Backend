package com.zachary.BI.scheduler;

import com.zachary.BI.BiMq.BiMessageProducer;
import com.zachary.BI.BiMq.BiMqConstant;
import com.zachary.BI.BiMq.RetryDelayPolicy;
import com.zachary.BI.config.AnalysisRecoveryProperties;
import com.zachary.BI.config.AnalysisRetryProperties;
import com.zachary.BI.model.entity.AnalysisJob;
import com.zachary.BI.service.AnalysisJobService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    @Test
    void republish_whileTheAnalysisQueueHasABacklog_shouldLeaveStaleJobsAlone() {
        // Their messages are most likely still waiting in that backlog; a republish would only add duplicates.
        PendingFixture fixture = new PendingFixture(new QueueInformation(BiMqConstant.BI_QUEUE_NAME, 40, 3));

        fixture.task.republishStalePendingJobs();

        verifyNoInteractions(fixture.jobs, fixture.producer);
    }

    @Test
    void republish_whenTheAnalysisQueueIsEmpty_shouldRepublishStaleJobs() {
        PendingFixture fixture = new PendingFixture(new QueueInformation(BiMqConstant.BI_QUEUE_NAME, 0, 3));
        AnalysisJob stale = new AnalysisJob();
        stale.setId(9L);
        stale.setStatus("queued");
        when(fixture.jobs.listStalePending(600L, 100)).thenReturn(List.of(stale));
        when(fixture.jobs.claimStalePending(9L, 600L)).thenReturn(true);

        fixture.task.republishStalePendingJobs();

        verify(fixture.producer).sendMessage(9L);
    }

    @Test
    void republish_whenTheQueueDepthCannotBeRead_shouldSkipThisRun() {
        PendingFixture fixture = new PendingFixture(null);
        when(fixture.amqpAdmin.getQueueInfo(BiMqConstant.BI_QUEUE_NAME)).thenThrow(new AmqpConnectException(null));

        fixture.task.republishStalePendingJobs();

        verifyNoInteractions(fixture.jobs, fixture.producer);
    }

    private static final class PendingFixture {
        final AnalysisJobService jobs = mock(AnalysisJobService.class);
        final BiMessageProducer producer = mock(BiMessageProducer.class);
        final AmqpAdmin amqpAdmin = mock(AmqpAdmin.class);
        final AnalysisJobRecoveryTask task = task(Duration.ofMinutes(3), Duration.ofMinutes(10));

        PendingFixture(QueueInformation queue) {
            when(amqpAdmin.getQueueInfo(BiMqConstant.BI_QUEUE_NAME)).thenReturn(queue);
            ReflectionTestUtils.setField(task, "analysisJobService", jobs);
            ReflectionTestUtils.setField(task, "biMessageProducer", producer);
            ReflectionTestUtils.setField(task, "amqpAdmin", amqpAdmin);
        }
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
