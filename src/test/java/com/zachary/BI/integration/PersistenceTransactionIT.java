package com.zachary.BI.integration;

import com.zachary.BI.exception.BusinessException;
import com.zachary.BI.integration.support.AbstractIntegrationTest;
import com.zachary.BI.service.AnalysisCompletionService;
import com.zachary.BI.service.AnalysisJobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies transactional guarantees that mocks cannot prove: they depend on Spring proxies and MySQL.
 */
class PersistenceTransactionIT extends AbstractIntegrationTest {

    @Autowired
    private AnalysisCompletionService analysisCompletionService;

    @Autowired
    private AnalysisJobService analysisJobService;

    @Test
    void persistSuccess_shouldCommitChartJobAndEventTogether() {
        long userId = 900_001L;
        long chartId = insertChart(userId);
        long jobId = analysisJobService.create(chartId, userId, fingerprint()).getId();
        assertThat(analysisJobService.start(jobId)).isTrue();

        analysisCompletionService.persistSuccess(jobId, chartId, "{\"series\":[]}", "Looks good");

        assertThat(chartRow(chartId)).containsEntry("status", "succeeded").containsEntry("genResult", "Looks good");
        assertThat(jobRow(jobId)).containsEntry("status", "succeeded");
        assertThat(eventCount(jobId, "succeeded")).isEqualTo(1);
    }

    @Test
    void persistSuccess_whenJobTransitionFails_shouldRollBackChartUpdate() {
        long userId = 900_002L;
        long chartId = insertChart(userId);
        // The job was never started, so succeed() cannot move it from running and throws.
        long jobId = analysisJobService.create(chartId, userId, fingerprint()).getId();

        assertThatThrownBy(() -> analysisCompletionService.persistSuccess(jobId, chartId, "{}", "Should not persist"))
                .isInstanceOf(BusinessException.class);

        assertThat(chartRow(chartId))
                .containsEntry("status", "queued")
                .containsEntry("genResult", null)
                .containsEntry("generatedAt", null);
        assertThat(jobRow(jobId)).containsEntry("status", "queued");
        assertThat(eventCount(jobId, "succeeded")).isZero();
    }

    @Test
    void activeFingerprintUniqueKey_shouldPreventDuplicateActiveJobs() {
        long userId = 900_003L;
        String fingerprint = fingerprint();
        long firstJob = analysisJobService.create(insertChart(userId), userId, fingerprint).getId();

        assertThatThrownBy(() -> analysisJobService.create(insertChart(userId), userId, fingerprint))
                .isInstanceOf(DuplicateKeyException.class);

        // Once the first job reaches a terminal state the key is released (NULLs are not unique in MySQL).
        analysisJobService.fail(firstJob, "done");
        assertThat(analysisJobService.create(insertChart(userId), userId, fingerprint).getId()).isNotEqualTo(firstJob);
    }

    @Test
    void requeueAfterPersistenceFailure_shouldReturnJobAndChartToQueue() {
        long userId = 900_004L;
        long chartId = insertChart(userId);
        long jobId = analysisJobService.create(chartId, userId, fingerprint()).getId();
        analysisJobService.start(jobId);

        analysisJobService.requeueAfterPersistenceFailure(jobId, "Deadlock found");

        assertThat(jobRow(jobId)).containsEntry("status", "queued").containsEntry("failureReason", "Deadlock found");
        assertThat(chartRow(chartId)).containsEntry("status", "queued");
        assertThat(eventCount(jobId, "queued")).isEqualTo(2);
    }

    private long insertChart(long userId) {
        jdbcTemplate.update("insert into chart (userId, name, goal, status) values (?, 'tx', 'goal', 'queued')", userId);
        return jdbcTemplate.queryForObject("select max(id) from chart where userId = ?", Long.class, userId);
    }

    private Map<String, Object> chartRow(long chartId) {
        return jdbcTemplate.queryForMap("select status, genResult, generatedAt from chart where id = ?", chartId);
    }

    private Map<String, Object> jobRow(long jobId) {
        return jdbcTemplate.queryForMap("select status, failureReason from analysis_job where id = ?", jobId);
    }

    private int eventCount(long jobId, String status) {
        return jdbcTemplate.queryForObject("select count(*) from analysis_job_event where jobId = ? and status = ?",
                Integer.class, jobId, status);
    }

    private static String fingerprint() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }
}
