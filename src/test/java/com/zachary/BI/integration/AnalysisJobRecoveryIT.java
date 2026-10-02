package com.zachary.BI.integration;

import com.zachary.BI.BiMq.BiMqConstant;
import com.zachary.BI.integration.support.AbstractIntegrationTest;
import com.zachary.BI.scheduler.AnalysisJobRecoveryTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reproduces the failure modes the normal message flow cannot recover from on its own, then runs the
 * recovery task directly instead of waiting for its schedule.
 */
class AnalysisJobRecoveryIT extends AbstractIntegrationTest {

    private static final byte[] CSV = "month,sales\nJan,10\nFeb,12\n".getBytes(StandardCharsets.UTF_8);
    private static final String AI_RESPONSE = "{\"xAxis\":{}}\n-----\nSales grow.";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** Well beyond the default ten-minute stale thresholds. */
    private static final long ONE_HOUR_MILLIS = 3_600_000L;

    @Autowired
    private AnalysisJobRecoveryTask recoveryTask;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @AfterEach
    void resumeConsumer() {
        listenerRegistry.start();
    }

    @Test
    void runningJobAbandonedByCrashedWorker_shouldBeRetriedAndSucceed() throws Exception {
        when(genAi.doChat(anyString())).thenReturn(AI_RESPONSE);
        Submitted job = submitWithConsumerStopped();
        // The worker set running, then the process died before the AI call returned.
        jdbcTemplate.update("update analysis_job set status = 'running', startedAt = ? where id = ?",
                new Date(System.currentTimeMillis() - ONE_HOUR_MILLIS), job.jobId());

        // The original message is redelivered, but start() refuses a running job, so it is acknowledged and dropped.
        listenerRegistry.start();
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> "running".equals(jobStatus(job.jobId())));
        verify(genAi, never()).doChat(anyString());

        recoveryTask.recover();

        awaitJobStatus(job.jobId(), "succeeded");
        assertThat(jdbcTemplate.queryForObject("select retryCount from analysis_job where id = ?", Integer.class,
                job.jobId())).as("a reclaimed run consumes one retry").isEqualTo(1);
        assertThat(chartStatus(job.chartId())).isEqualTo("succeeded");
    }

    @Test
    void abandonedJobWithoutRetriesLeft_shouldFailAndReleaseDedupKey() throws Exception {
        Submitted job = submitWithConsumerStopped();
        purgeAnalysisQueue();
        jdbcTemplate.update("update analysis_job set status = 'running', startedAt = ?, retryCount = maxRetries "
                + "where id = ?", new Date(System.currentTimeMillis() - ONE_HOUR_MILLIS), job.jobId());

        recoveryTask.recover();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, activeFingerprint, finishedAt from analysis_job where id = ?", job.jobId());
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat(row.get("activeFingerprint")).isNull();
        assertThat(row.get("finishedAt")).isNotNull();
        assertThat(chartStatus(job.chartId())).isEqualTo("failed");
    }

    @Test
    void queuedJobWhoseMessageWasLost_shouldBeRepublishedAndSucceed() throws Exception {
        when(genAi.doChat(anyString())).thenReturn(AI_RESPONSE);
        Submitted job = submitWithConsumerStopped();
        // Simulates a crash between committing the job and publishing its message.
        purgeAnalysisQueue();
        // updateTime is compared with MySQL's NOW(), so age it with the database clock too.
        jdbcTemplate.update("update analysis_job set updateTime = NOW() - INTERVAL 1 HOUR where id = ?", job.jobId());
        listenerRegistry.start();

        recoveryTask.recover();

        awaitJobStatus(job.jobId(), "succeeded");
    }

    @Test
    void recentlyStartedAndRecentlyQueuedJobs_shouldBeLeftAlone() throws Exception {
        Submitted running = submitWithConsumerStopped();
        Submitted queued = submitWithConsumerStopped("Still queued");
        purgeAnalysisQueue();
        jdbcTemplate.update("update analysis_job set status = 'running', startedAt = ? where id = ?",
                new Date(), running.jobId());

        recoveryTask.recover();

        assertThat(jobStatus(running.jobId())).isEqualTo("running");
        assertThat(jobStatus(queued.jobId())).isEqualTo("queued");
        assertThat(rabbitTemplate.receive(BiMqConstant.BI_QUEUE_NAME, 500))
                .as("nothing republished").isNull();

        // Leave no active jobs behind for later tests.
        jdbcTemplate.update("update analysis_job set status = 'cancelled', activeFingerprint = null where id in (?, ?)",
                running.jobId(), queued.jobId());
    }

    // region helpers

    private record Submitted(long jobId, long chartId) {
    }

    private Submitted submitWithConsumerStopped() throws Exception {
        return submitWithConsumerStopped("Recovery");
    }

    private Submitted submitWithConsumerStopped(String name) throws Exception {
        listenerRegistry.stop();
        TestUser user = registerAndLogin();
        String fileToken = uploadFile(user, "sales.csv", CSV);
        var submitted = assertSuccess(postJson("/chart/gen", user.session(), Map.of(
                "fileToken", fileToken,
                "name", name,
                "goal", "Show the trend",
                "chartType", "line",
                "qualityAcknowledged", false)));
        return new Submitted(submitted.get("jobId").asLong(), submitted.get("chartId").asLong());
    }

    private void purgeAnalysisQueue() {
        rabbitTemplate.execute(channel -> channel.queuePurge(BiMqConstant.BI_QUEUE_NAME));
    }

    private String jobStatus(long jobId) {
        return jdbcTemplate.queryForObject("select status from analysis_job where id = ?", String.class, jobId);
    }

    private String chartStatus(long chartId) {
        return jdbcTemplate.queryForObject("select status from chart where id = ?", String.class, chartId);
    }

    private void awaitJobStatus(long jobId, String expected) {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(jobStatus(jobId)).isEqualTo(expected));
    }

    // endregion
}
