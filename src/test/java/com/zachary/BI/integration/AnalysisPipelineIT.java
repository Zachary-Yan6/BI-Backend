package com.zachary.BI.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zachary.BI.BiMq.BiMqConstant;
import com.zachary.BI.integration.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class AnalysisPipelineIT extends AbstractIntegrationTest {

    private static final byte[] CSV = "month,sales\nJan,10\nFeb,12\nMar,15\n".getBytes(StandardCharsets.UTF_8);
    private static final String AI_RESPONSE =
            "```javascript\n{\"xAxis\":{\"data\":[\"Jan\",\"Feb\",\"Mar\"]}}\n```\n-----\nSales grow every month.";
    /** Covers the 2s retry-queue TTL plus broker and consumer latency. */
    private static final Duration PIPELINE_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @AfterEach
    void resumeConsumer() {
        listenerRegistry.start();
    }

    @Test
    void uploadedFile_shouldBeAnalysedAsynchronouslyAndPersisted() throws Exception {
        when(genAi.doChat(anyString())).thenReturn(AI_RESPONSE);
        TestUser user = registerAndLogin();
        String fileToken = uploadFile(user, "sales.csv", CSV);

        JsonNode submitted = submit(user, fileToken, "Monthly sales");
        long chartId = submitted.get("chartId").asLong();
        long jobId = submitted.get("jobId").asLong();
        assertThat(submitted.get("reused").asBoolean()).isFalse();

        awaitJobStatus(jobId, "succeeded");

        Map<String, Object> chart = jdbcTemplate.queryForMap("select * from chart where id = ?", chartId);
        assertThat(chart.get("status")).isEqualTo("succeeded");
        assertThat(chart.get("genChart")).isEqualTo("{\"xAxis\":{\"data\":[\"Jan\",\"Feb\",\"Mar\"]}}");
        assertThat(chart.get("genResult")).isEqualTo("Sales grow every month.");
        assertThat(chart.get("generatedAt")).isNotNull();
        assertThat(chart.get("sourceFileName")).isEqualTo("sales.csv");
        assertThat(chart.get("sourceFileSize")).isEqualTo((long) CSV.length);
        assertThat(chart.get("chartData")).isEqualTo(new String(CSV, StandardCharsets.UTF_8));

        Map<String, Object> job = jdbcTemplate.queryForMap("select * from analysis_job where id = ?", jobId);
        assertThat(job.get("activeFingerprint")).as("finished jobs release the dedup key").isNull();
        assertThat(job.get("finishedAt")).isNotNull();
        assertThat(eventStatuses(user, jobId)).containsExactlyInAnyOrder("queued", "running", "succeeded");

        JsonNode jobs = assertSuccess(postJson("/chart/job/list-by-chart", user.session(),
                Map.of("chartIds", List.of(chartId))));
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).get("status").asText()).isEqualTo("succeeded");

        verify(genAi).doChat(contains("My goal: Show the monthly trend"));
    }

    @Test
    void legacyMultipartUpload_shouldRunThroughSamePipeline() throws Exception {
        when(genAi.doChat(anyString())).thenReturn(AI_RESPONSE);
        TestUser user = registerAndLogin();

        JsonNode submitted = assertSuccess(perform(multipart("/chart/gen")
                .file(new MockMultipartFile("file", "sales.csv", "text/csv", CSV))
                .param("name", "Multipart sales")
                .param("goal", "Show the monthly trend")
                .param("chartType", "bar")
                .session(user.session())));

        awaitJobStatus(submitted.get("jobId").asLong(), "succeeded");
        assertThat(jdbcTemplate.queryForObject("select chartType from chart where id = ?", String.class,
                submitted.get("chartId").asLong())).isEqualTo("bar");
    }

    @Test
    void transientAiFailure_shouldBeRetriedThroughDelayedRetryQueue() throws Exception {
        when(genAi.doChat(anyString()))
                .thenThrow(new IllegalStateException("AI provider timeout"))
                .thenReturn(AI_RESPONSE);
        TestUser user = registerAndLogin();
        long jobId = submit(user, uploadFile(user, "sales.csv", CSV), "Flaky AI").get("jobId").asLong();

        awaitJobStatus(jobId, "succeeded");

        Map<String, Object> job = jdbcTemplate.queryForMap("select retryCount, failureReason from analysis_job where id = ?", jobId);
        assertThat(job.get("retryCount")).isEqualTo(1);
        assertThat(job.get("failureReason")).as("cleared on success").isNull();
        // createTime has one-second resolution, so events in the same second have no guaranteed order.
        assertThat(eventStatuses(user, jobId)).contains("queued", "running", "retrying", "succeeded");
    }

    @Test
    void jsonFencedAiAnswer_shouldBeStoredAsCleanJson() throws Exception {
        when(genAi.doChat(anyString())).thenReturn(
                "```json\n{\n  \"xAxis\": {\"data\": [\"Jan\", \"Feb\", \"Mar\"]}\n}\n```\n-----\nSales grow every month.");
        TestUser user = registerAndLogin();
        JsonNode submitted = submit(user, uploadFile(user, "sales.csv", CSV), "Json fence");

        awaitJobStatus(submitted.get("jobId").asLong(), "succeeded");

        Map<String, Object> chart = jdbcTemplate.queryForMap("select genChart, genResult from chart where id = ?",
                submitted.get("chartId").asLong());
        assertThat(chart.get("genChart")).isEqualTo("{\"xAxis\":{\"data\":[\"Jan\",\"Feb\",\"Mar\"]}}");
        assertThat(chart.get("genResult")).isEqualTo("Sales grow every month.");
    }

    @Test
    void invalidAiChartOption_shouldBeRetriedInsteadOfStored() throws Exception {
        when(genAi.doChat(anyString()))
                .thenReturn("{\"tooltip\":{\"formatter\":function (p) { return p.name; }}}\n-----\nNot JSON.")
                .thenReturn(AI_RESPONSE);
        TestUser user = registerAndLogin();
        long jobId = submit(user, uploadFile(user, "sales.csv", CSV), "Invalid option").get("jobId").asLong();

        awaitJobStatus(jobId, "succeeded");

        assertThat(jdbcTemplate.queryForObject("select retryCount from analysis_job where id = ?", Integer.class,
                jobId)).isEqualTo(1);
        assertThat(eventStatuses(user, jobId)).contains("retrying", "succeeded");
    }

    @Test
    void exhaustedRetries_shouldFailJobAndPublishToDeadLetterQueue_thenUserCanRetry() throws Exception {
        TestUser user = registerAndLogin();
        String fileToken = uploadFile(user, "sales.csv", CSV);

        listenerRegistry.stop();
        JsonNode submitted = submit(user, fileToken, "Always failing");
        long jobId = submitted.get("jobId").asLong();
        long chartId = submitted.get("chartId").asLong();
        // No retry budget, so the first failure is terminal.
        jdbcTemplate.update("update analysis_job set maxRetries = 0 where id = ?", jobId);
        when(genAi.doChat(anyString())).thenThrow(new IllegalStateException("AI provider down"));
        listenerRegistry.start();

        awaitJobStatus(jobId, "failed");
        assertThat(jdbcTemplate.queryForMap("select status, execMessage from chart where id = ?", chartId))
                .containsEntry("status", "failed")
                .containsEntry("execMessage", "AI provider down");
        assertThat(drainDeadLetterQueueUntil(String.valueOf(jobId))).isTrue();

        // A failed job can be re-run by its owner once the provider recovers.
        // doReturn avoids invoking the currently throwing stub while re-stubbing it.
        doReturn(AI_RESPONSE).when(genAi).doChat(anyString());
        assertThat(assertSuccess(perform(post("/chart/job/{id}/retry", jobId).session(user.session()))).asBoolean())
                .isTrue();

        awaitJobStatus(jobId, "succeeded");
        assertThat(jdbcTemplate.queryForObject("select status from chart where id = ?", String.class, chartId))
                .isEqualTo("succeeded");
    }

    @Test
    void identicalSubmissionWhileActive_shouldReuseExistingJob() throws Exception {
        when(genAi.doChat(anyString())).thenReturn(AI_RESPONSE);
        TestUser user = registerAndLogin();
        String fileToken = uploadFile(user, "sales.csv", CSV);

        listenerRegistry.stop();
        JsonNode first = submit(user, fileToken, "Deduplicated");
        JsonNode second = submit(user, fileToken, "Deduplicated");

        assertThat(second.get("reused").asBoolean()).isTrue();
        assertThat(second.get("jobId").asLong()).isEqualTo(first.get("jobId").asLong());
        assertThat(second.get("chartId").asLong()).isEqualTo(first.get("chartId").asLong());
        assertThat(jdbcTemplate.queryForObject("select count(*) from analysis_job where userId = ?", Integer.class,
                user.id())).isEqualTo(1);

        listenerRegistry.start();
        awaitJobStatus(first.get("jobId").asLong(), "succeeded");
    }

    @Test
    void cancelledQueuedJob_shouldBeSkippedByConsumerAndFreeTheDedupKey() throws Exception {
        TestUser user = registerAndLogin();
        String fileToken = uploadFile(user, "sales.csv", CSV);

        listenerRegistry.stop();
        JsonNode submitted = submit(user, fileToken, "Cancelled");
        long jobId = submitted.get("jobId").asLong();

        assertThat(assertSuccess(perform(post("/chart/job/{id}/cancel", jobId).session(user.session()))).asBoolean())
                .isTrue();
        assertThat(jdbcTemplate.queryForObject("select status from chart where id = ?", String.class,
                submitted.get("chartId").asLong())).isEqualTo("cancelled");

        listenerRegistry.start();
        // The queued message is still delivered, but the consumer must acknowledge it without analysing.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> "cancelled".equals(jobStatus(jobId)));
        verify(genAi, never()).doChat(anyString());

        // The unique active-fingerprint key was released, so the same analysis can be requested again.
        when(genAi.doChat(anyString())).thenReturn(AI_RESPONSE);
        JsonNode resubmitted = submit(user, fileToken, "Cancelled");
        assertThat(resubmitted.get("reused").asBoolean()).isFalse();
        assertThat(resubmitted.get("jobId").asLong()).isNotEqualTo(jobId);
        awaitJobStatus(resubmitted.get("jobId").asLong(), "succeeded");
    }

    @Test
    void jobOperations_shouldRespectOwnershipAndState() throws Exception {
        when(genAi.doChat(anyString())).thenReturn(AI_RESPONSE);
        TestUser owner = registerAndLogin();
        TestUser stranger = registerAndLogin();
        JsonNode submitted = submit(owner, uploadFile(owner, "sales.csv", CSV), "Owned");
        long jobId = submitted.get("jobId").asLong();
        long chartId = submitted.get("chartId").asLong();
        awaitJobStatus(jobId, "succeeded");

        assertErrorCode(perform(get("/chart/job/{id}/events", jobId).session(stranger.session())), 40400);
        assertErrorCode(perform(post("/chart/job/{id}/cancel", jobId).session(stranger.session())), 40400);
        assertErrorCode(perform(post("/chart/job/{id}/retry", jobId).session(stranger.session())), 50001);
        assertThat(assertSuccess(postJson("/chart/job/list-by-chart", stranger.session(),
                Map.of("chartIds", List.of(chartId))))).isEmpty();

        // Only failed jobs can be retried and only queued/retrying jobs can be cancelled.
        assertErrorCode(perform(post("/chart/job/{id}/retry", jobId).session(owner.session())), 50001);
        assertErrorCode(perform(post("/chart/job/{id}/cancel", jobId).session(owner.session())), 50001);
    }

    @Test
    void qualityIssues_shouldBlockAnalysisUntilAcknowledged() throws Exception {
        when(genAi.doChat(anyString())).thenReturn(AI_RESPONSE);
        TestUser user = registerAndLogin();
        String fileToken = uploadFile(user, "gaps.csv", "month,sales\nJan,\nFeb,\n".getBytes(StandardCharsets.UTF_8));

        JsonNode report = assertSuccess(postJson("/chart/quality-check", user.session(), Map.of("fileToken", fileToken)));
        assertThat(report.get("confirmationRequired").asBoolean()).isTrue();

        assertErrorCode(postJson("/chart/gen", user.session(), generationRequest(fileToken, "Gaps", false)), 40000);
        assertThat(jdbcTemplate.queryForObject("select count(*) from chart where userId = ?", Integer.class, user.id()))
                .isZero();

        long jobId = assertSuccess(postJson("/chart/gen", user.session(), generationRequest(fileToken, "Gaps", true)))
                .get("jobId").asLong();
        awaitJobStatus(jobId, "succeeded");
    }

    @Test
    void generationRequests_shouldBeRateLimitedPerUserInRedis() throws Exception {
        when(genAi.doChat(anyString())).thenReturn(AI_RESPONSE);
        TestUser user = registerAndLogin();
        String fileToken = uploadFile(user, "sales.csv", CSV);

        // The sliding window allows two generations per user per second; names differ to avoid dedup reuse.
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            codes.add(postJson("/chart/gen", user.session(), generationRequest(fileToken, "Burst " + i, false))
                    .get("code").asInt());
        }

        assertThat(codes).containsExactly(0, 0, 50000);
        assertThat(jdbcTemplate.queryForObject("select count(*) from chart where userId = ?", Integer.class, user.id()))
                .isEqualTo(2);

        // Another user has an independent window.
        TestUser other = registerAndLogin();
        assertSuccess(postJson("/chart/gen", other.session(),
                generationRequest(uploadFile(other, "sales.csv", CSV), "Other", false)));

        // Let the accepted jobs finish so they cannot call the AI mock during later tests.
        for (TestUser owner : List.of(user, other)) {
            for (Long jobId : jdbcTemplate.queryForList("select id from analysis_job where userId = ?", Long.class,
                    owner.id())) {
                awaitJobStatus(jobId, "succeeded");
            }
        }
    }

    // region helpers

    private JsonNode submit(TestUser user, String fileToken, String name) throws Exception {
        return assertSuccess(postJson("/chart/gen", user.session(), generationRequest(fileToken, name, false)));
    }

    private static Map<String, Object> generationRequest(String fileToken, String name, boolean acknowledged) {
        return Map.of(
                "fileToken", fileToken,
                "name", name,
                "goal", "Show the monthly trend",
                "chartType", "line",
                "qualityAcknowledged", acknowledged);
    }

    private String jobStatus(long jobId) {
        return jdbcTemplate.queryForObject("select status from analysis_job where id = ?", String.class, jobId);
    }

    private void awaitJobStatus(long jobId, String expected) {
        await().atMost(PIPELINE_TIMEOUT).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(jobStatus(jobId)).isEqualTo(expected));
    }

    private List<String> eventStatuses(TestUser user, long jobId) throws Exception {
        JsonNode events = assertSuccess(perform(get("/chart/job/{id}/events", jobId).session(user.session())));
        List<String> statuses = new ArrayList<>();
        events.forEach(event -> statuses.add(event.get("status").asText()));
        return statuses;
    }

    private boolean drainDeadLetterQueueUntil(String expectedJobId) {
        Object message;
        while ((message = rabbitTemplate.receiveAndConvert(BiMqConstant.DEAD_LETTER_QUEUE_NAME, 10_000)) != null) {
            if (expectedJobId.equals(message)) {
                return true;
            }
        }
        return false;
    }

    // endregion
}
