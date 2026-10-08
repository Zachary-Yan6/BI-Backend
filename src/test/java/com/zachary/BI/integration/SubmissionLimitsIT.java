package com.zachary.BI.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zachary.BI.BiMq.BiMqConstant;
import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.exception.BusinessException;
import com.zachary.BI.integration.support.AbstractIntegrationTest;
import com.zachary.BI.scheduler.AnalysisJobRecoveryTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Limits on how many analyses can be in progress, and what the recovery task does while a backlog drains.
 */
class SubmissionLimitsIT extends AbstractIntegrationTest {

    private static final byte[] CSV = "month,sales\nJan,10\nFeb,12\n".getBytes(StandardCharsets.UTF_8);
    private static final AtomicLong JOB_IDS = new AtomicLong(8_100_000_000_000L);

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AnalysisJobRecoveryTask recoveryTask;

    @AfterEach
    void cleanUp() {
        // Leave no active jobs or queued messages behind for later tests.
        jdbcTemplate.update("update analysis_job set status = 'cancelled', activeFingerprint = null "
                + "where status in ('queued', 'running', 'retrying')");
        rabbitTemplate.execute(channel -> channel.queuePurge(BiMqConstant.BI_QUEUE_NAME));
        listenerRegistry.start();
    }

    @Test
    void userWithFiveActiveAnalyses_shouldBeRefusedANewOneButStillReuseAnIdenticalOne() throws Exception {
        listenerRegistry.stop();
        TestUser user = registerAndLogin();
        String fileToken = uploadFile(user, "sales.csv", CSV);
        long firstJob = submit(user, fileToken, "Report 1").get("jobId").asLong();
        long[] others = new long[4];
        for (int i = 0; i < others.length; i++) {
            others[i] = insertActiveJob(user.id());
        }

        // Same name and file as an active job: reused, not counted again.
        JsonNode identical = submit(user, fileToken, "Report 1");
        assertThat(identical.get("reused").asBoolean()).isTrue();
        assertThat(identical.get("jobId").asLong()).isEqualTo(firstJob);

        // A sixth, different analysis: refused before anything is saved or queued.
        int chartsBefore = chartCount(user);
        assertErrorCode(postJson("/chart/gen", user.session(), request(fileToken, "Report 6")),
                ErrorCode.TOO_MANY_REQUESTS.getCode());
        assertThat(chartCount(user)).isEqualTo(chartsBefore);

        // Once one finishes there is room again.
        jdbcTemplate.update("update analysis_job set status = 'succeeded', activeFingerprint = null where id = ?",
                others[0]);
        Thread.sleep(1_100); // the per-user rate limit allows two submissions per second
        assertThat(submit(user, fileToken, "Report 6").get("reused").asBoolean()).isFalse();
    }

    @Test
    void concurrentSubmissionsByOneUser_shouldNotBothTakeTheLastSlot() throws Exception {
        TestUser user = registerAndLogin();
        for (int i = 0; i < 4; i++) {
            insertActiveJob(user.id());
        }
        CountDownLatch firstHoldsTheLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        // The first submission passes the check and inserts the fifth job, but has not committed yet.
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(tx -> {
            analysisJobService.checkCapacity(user.id());
            analysisJobService.create(0L, user.id(), fingerprint());
            firstHoldsTheLock.countDown();
            await(releaseFirst);
        }));
        assertThat(firstHoldsTheLock.await(10, TimeUnit.SECONDS)).isTrue();

        // Without the row lock the second would count 4 committed jobs and also pass.
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(
                tx -> analysisJobService.checkCapacity(user.id())));
        Thread.sleep(500);
        assertThat(second).as("waits for the first submission's lock").isNotDone();

        releaseFirst.countDown();
        first.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(BusinessException.class)
                .hasMessageContaining("5 analyses in progress");
    }

    @Test
    void staleQueuedJob_shouldNotBeRepublishedWhileTheQueueHasABacklog() throws Exception {
        listenerRegistry.stop();
        TestUser user = registerAndLogin();
        String fileToken = uploadFile(user, "sales.csv", CSV);
        submit(user, fileToken, "Waiting in the backlog");
        Thread.sleep(1_100);
        long waiting = jdbcTemplate.queryForObject("select max(id) from analysis_job where userId = ?", Long.class,
                user.id());
        long stale = submit(user, fileToken, "Stale").get("jobId").asLong();
        jdbcTemplate.update("update analysis_job set updateTime = NOW() - INTERVAL 1 HOUR where id = ?", stale);
        assertThat(queueDepth()).isEqualTo(2);

        recoveryTask.recover();

        assertThat(queueDepth()).as("its message may simply be waiting, so nothing is added").isEqualTo(2);

        // Once the queue has drained, a job still queued can no longer be waiting for a message: republish it.
        rabbitTemplate.execute(channel -> channel.queuePurge(BiMqConstant.BI_QUEUE_NAME));
        recoveryTask.recover();

        List<Object> republished = new ArrayList<>();
        Object message;
        while ((message = rabbitTemplate.receiveAndConvert(BiMqConstant.BI_QUEUE_NAME, 1_000)) != null) {
            republished.add(message);
        }
        assertThat(republished).contains(String.valueOf(stale)).doesNotContain(String.valueOf(waiting));
    }

    private JsonNode submit(TestUser user, String fileToken, String name) throws Exception {
        return assertSuccess(postJson("/chart/gen", user.session(), request(fileToken, name)));
    }

    private static Map<String, Object> request(String fileToken, String name) {
        return Map.of("fileToken", fileToken, "name", name, "goal", "Show the trend", "chartType", "line",
                "qualityAcknowledged", false);
    }

    private long insertActiveJob(long userId) {
        long id = JOB_IDS.incrementAndGet();
        String fingerprint = fingerprint();
        jdbcTemplate.update("insert into analysis_job (id, chartId, userId, requestId, dataFingerprint, "
                        + "activeFingerprint, status) values (?, 0, ?, ?, ?, ?, 'running')",
                id, userId, UUID.randomUUID().toString(), fingerprint, fingerprint);
        return id;
    }

    private int chartCount(TestUser user) {
        return jdbcTemplate.queryForObject("select count(*) from chart where userId = ?", Integer.class, user.id());
    }

    private int queueDepth() {
        return amqpAdmin.getQueueInfo(BiMqConstant.BI_QUEUE_NAME).getMessageCount();
    }

    private static String fingerprint() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
