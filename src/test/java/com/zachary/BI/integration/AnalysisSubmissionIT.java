package com.zachary.BI.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zachary.BI.integration.support.AbstractIntegrationTest;
import com.zachary.BI.service.AnalysisJobService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Failure injection around job creation, checked against real MySQL transactions.
 * The spy makes this class use its own Spring context; the containers are still shared.
 */
class AnalysisSubmissionIT extends AbstractIntegrationTest {

    private static final byte[] CSV = "month,sales\nJan,10\nFeb,12\n".getBytes(StandardCharsets.UTF_8);

    @MockitoSpyBean
    private AnalysisJobService analysisJobService;

    @Test
    void failedJobInsert_shouldNotLeaveAnOrphanChart() throws Exception {
        TestUser user = registerAndLogin();
        String fileToken = uploadFile(user, "sales.csv", CSV);
        doThrow(new DataIntegrityViolationException("simulated job insert failure"))
                .when(analysisJobService).create(anyLong(), eq(user.id()), anyString());

        assertErrorCode(submit(user, fileToken), 50000);

        // Previously the chart was committed on its own and stayed "queued" forever with no job behind it.
        assertThat(chartCount(user)).isZero();
    }

    @Test
    void duplicateWhoseWinnerAlreadyFinished_shouldRetryInsteadOfFailing() throws Exception {
        when(genAi.doChat(anyString())).thenReturn("{\"xAxis\":{}}\n-----\nSales grow.");
        TestUser user = registerAndLogin();
        String fileToken = uploadFile(user, "sales.csv", CSV);
        // First insert hits the dedup key of a job that finishes immediately afterwards, so the follow-up lookup
        // finds nothing. Previously that rethrew the DuplicateKeyException as a 500.
        doThrow(new DuplicateKeyException("simulated concurrent duplicate"))
                .doCallRealMethod()
                .when(analysisJobService).create(anyLong(), eq(user.id()), anyString());

        JsonNode submitted = assertSuccess(submit(user, fileToken));

        assertThat(submitted.get("reused").asBoolean()).isFalse();
        assertThat(chartCount(user)).as("the failed attempt's chart was rolled back").isEqualTo(1);
        long jobId = submitted.get("jobId").asLong();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                "select status from analysis_job where id = ?", String.class, jobId)).isEqualTo("succeeded"));
    }

    private JsonNode submit(TestUser user, String fileToken) throws Exception {
        return postJson("/chart/gen", user.session(), Map.of(
                "fileToken", fileToken,
                "name", "Submission",
                "goal", "Show the trend",
                "chartType", "line",
                "qualityAcknowledged", false));
    }

    private int chartCount(TestUser user) {
        return jdbcTemplate.queryForObject("select count(*) from chart where userId = ?", Integer.class, user.id());
    }
}
