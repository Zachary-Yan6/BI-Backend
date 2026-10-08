package com.zachary.BI.service.impl;

import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.config.AnalysisLimitProperties;
import com.zachary.BI.config.AnalysisRetryProperties;
import com.zachary.BI.exception.BusinessException;
import com.zachary.BI.mapper.AnalysisJobEventMapper;
import com.zachary.BI.mapper.AnalysisJobMapper;
import com.zachary.BI.mapper.UserMapper;
import com.zachary.BI.model.entity.AnalysisJob;
import com.zachary.BI.model.entity.AnalysisJobEvent;
import com.zachary.BI.model.entity.Chart;
import com.zachary.BI.model.enums.AnalysisJobStatusEnum;
import com.zachary.BI.service.ChartService;
import com.zachary.BI.support.MybatisPlusTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisJobServiceImplTest {

    @Mock
    private AnalysisJobMapper analysisJobMapper;

    @Mock
    private AnalysisJobEventMapper analysisJobEventMapper;

    @Mock
    private ChartService chartService;

    @Mock
    private UserMapper userMapper;

    @Spy
    private AnalysisLimitProperties limitProperties = new AnalysisLimitProperties();

    @Spy
    private AnalysisRetryProperties retryProperties = new AnalysisRetryProperties();

    @InjectMocks
    private AnalysisJobServiceImpl analysisJobService;

    @BeforeAll
    static void initLambdaCache() {
        MybatisPlusTestSupport.initTableInfo(AnalysisJob.class, AnalysisJobEvent.class);
    }

    @Test
    void findActiveJob_shouldWrapMapperResult() {
        AnalysisJob job = job(1L, "queued");
        when(analysisJobMapper.selectOne(any())).thenReturn(job);

        assertSame(job, analysisJobService.findActiveJob(7L, "fp").orElseThrow());
    }

    @Test
    void findActiveJob_whenNoneExists_shouldBeEmpty() {
        assertTrue(analysisJobService.findActiveJob(7L, "fp").isEmpty());
    }

    @Test
    void create_shouldInsertQueuedJobAndEvent() {
        doAnswer(invocation -> {
            invocation.<AnalysisJob>getArgument(0).setId(11L);
            return 1;
        }).when(analysisJobMapper).insert(any(AnalysisJob.class));

        AnalysisJob job = analysisJobService.create(2L, 7L, "fp");

        assertEquals(11L, job.getId());
        assertEquals(2L, job.getChartId());
        assertEquals(7L, job.getUserId());
        assertEquals("fp", job.getDataFingerprint());
        assertEquals("fp", job.getActiveFingerprint());
        assertEquals("queued", job.getStatus());
        assertEquals(0, job.getRetryCount());
        assertEquals(3, job.getMaxRetries());
        assertNotNull(job.getRequestId());
        assertEquals("queued", capturedEvent().getStatus());
    }

    @Test
    void start_shouldRecordEventOnlyWhenJobWasClaimed() {
        when(analysisJobMapper.update(isNull(), any())).thenReturn(1, 0);

        assertTrue(analysisJobService.start(1L));
        assertFalse(analysisJobService.start(1L));

        assertEquals("running", capturedEvent().getStatus());
    }

    @Test
    void succeed_shouldRecordEvent() {
        when(analysisJobMapper.update(isNull(), any())).thenReturn(1);

        analysisJobService.succeed(1L);

        assertEquals("succeeded", capturedEvent().getStatus());
    }

    @Test
    void succeed_whenJobIsNotRunning_shouldThrow() {
        when(analysisJobMapper.update(isNull(), any())).thenReturn(0);

        assertBusinessError(ErrorCode.OPERATION_ERROR, () -> analysisJobService.succeed(1L));
        verifyNoInteractions(analysisJobEventMapper);
    }

    @Test
    void scheduleRetry_whenJobMissingOrNotRunning_shouldReturnMinusOne() {
        when(analysisJobMapper.selectById(1L)).thenReturn(null, job(1L, "queued"));

        assertEquals(-1, analysisJobService.scheduleRetry(1L, "boom"));
        assertEquals(-1, analysisJobService.scheduleRetry(1L, "boom"));
        verify(analysisJobMapper, never()).update(any(), any());
    }

    @Test
    void scheduleRetry_whenRetriesExhausted_shouldReturnZero() {
        AnalysisJob job = job(1L, "running");
        job.setRetryCount(3);
        job.setMaxRetries(3);
        when(analysisJobMapper.selectById(1L)).thenReturn(job);

        assertEquals(0, analysisJobService.scheduleRetry(1L, "boom"));
        verify(analysisJobMapper, never()).update(any(), any());
    }

    @Test
    void scheduleRetry_whenJobChangesAfterRead_shouldReturnMinusOneWithoutEvent() {
        AnalysisJob job = job(1L, "running");
        job.setRetryCount(0);
        job.setMaxRetries(3);
        when(analysisJobMapper.selectById(1L)).thenReturn(job);
        when(analysisJobMapper.update(isNull(), any())).thenReturn(0);

        assertEquals(-1, analysisJobService.scheduleRetry(1L, "boom"));
        verifyNoInteractions(analysisJobEventMapper);
    }

    @Test
    void scheduleRetry_shouldIncrementAttemptAndRecordEvent() {
        AnalysisJob job = job(1L, "running");
        job.setRetryCount(1);
        job.setMaxRetries(3);
        when(analysisJobMapper.selectById(1L)).thenReturn(job);
        when(analysisJobMapper.update(isNull(), any())).thenReturn(1);

        assertEquals(2, analysisJobService.scheduleRetry(1L, " "));

        verify(analysisJobMapper).update(isNull(), any());
        AnalysisJobEvent event = capturedEvent();
        assertEquals("retrying", event.getStatus());
        assertTrue(event.getMessage().contains("Attempt 2"));
    }

    @Test
    void fail_shouldUpdateAndRecordEventWithBoundedReason() {
        when(analysisJobMapper.update(isNull(), any())).thenReturn(1);

        analysisJobService.fail(1L, "x".repeat(2000));

        verify(analysisJobMapper).update(isNull(), any());
        AnalysisJobEvent event = capturedEvent();
        assertEquals("failed", event.getStatus());
        assertTrue(event.getMessage().startsWith("Analysis failed: xxx"));
        assertTrue(event.getMessage().length() <= 512);
    }

    @Test
    void fail_whenJobAlreadyFinished_shouldNotRecordContradictingEvent() {
        when(analysisJobMapper.update(isNull(), any())).thenReturn(0);

        analysisJobService.fail(1L, "late failure");

        verifyNoInteractions(analysisJobEventMapper);
    }

    @Test
    void failQueued_shouldOnlyFailJobsNoWorkerStarted() {
        when(analysisJobMapper.update(isNull(), any())).thenReturn(1, 0);

        assertTrue(analysisJobService.failQueued(1L, "Could not submit the job to the queue"));
        // 0 rows: the job had already left queued, e.g. a worker is running it.
        assertFalse(analysisJobService.failQueued(1L, "Could not submit the job to the queue"));

        AnalysisJobEvent event = capturedEvent();
        assertEquals("failed", event.getStatus());
        assertEquals("Analysis failed: Could not submit the job to the queue", event.getMessage());
    }

    @Test
    void failRunning_shouldOnlyFailJobsAWorkerIsRunning() {
        when(analysisJobMapper.update(isNull(), any())).thenReturn(1, 0);

        assertTrue(analysisJobService.failRunning(1L, "Not retryable: 401 Unauthorized"));
        // 0 rows: the recovery task already reclaimed the job, so its decision stands.
        assertFalse(analysisJobService.failRunning(1L, "Not retryable: 401 Unauthorized"));

        assertEquals("Analysis failed: Not retryable: 401 Unauthorized", capturedEvent().getMessage());
    }

    @Test
    void checkCapacity_shouldLockTheUserBeforeCountingAndAllowUnderBothLimits() {
        when(analysisJobMapper.selectCount(any())).thenReturn(4L, 199L);

        assertDoesNotThrow(() -> analysisJobService.checkCapacity(7L));

        // The lock must come first, or a concurrent submission by the same user could count the same 4 jobs.
        InOrder order = inOrder(userMapper, analysisJobMapper);
        order.verify(userMapper).lockById(7L);
        order.verify(analysisJobMapper, times(2)).selectCount(any());
    }

    @Test
    void checkCapacity_whenTheUserHasFiveActiveJobs_shouldReject() {
        when(analysisJobMapper.selectCount(any())).thenReturn(5L);

        BusinessException exception = assertThrows(BusinessException.class, () -> analysisJobService.checkCapacity(7L));

        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), exception.getCode());
        assertTrue(exception.getMessage().contains("5 analyses in progress"), exception.getMessage());
    }

    @Test
    void checkCapacity_whenTheSystemIsFull_shouldReject() {
        when(analysisJobMapper.selectCount(any())).thenReturn(0L, 200L);

        BusinessException exception = assertThrows(BusinessException.class, () -> analysisJobService.checkCapacity(7L));

        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), exception.getCode());
        assertTrue(exception.getMessage().contains("at capacity"), exception.getMessage());
    }

    @Test
    void cancel_shouldRecordEventOnlyWhenChanged() {
        when(analysisJobMapper.update(isNull(), any())).thenReturn(1, 0);

        assertTrue(analysisJobService.cancel(1L, 7L));
        assertFalse(analysisJobService.cancel(1L, 7L));

        assertEquals("cancelled", capturedEvent().getStatus());
    }

    @Test
    void retry_whenJobMissingOrNotFailed_shouldReturnFalse() {
        when(analysisJobMapper.selectOne(any())).thenReturn(null, job(1L, "running"));

        assertFalse(analysisJobService.retry(1L, 7L));
        assertFalse(analysisJobService.retry(1L, 7L));
    }

    @Test
    void retry_whenAnotherActiveJobUsesSameData_shouldReturnFalse() {
        AnalysisJob failed = job(1L, "failed");
        failed.setDataFingerprint("fp");
        when(analysisJobMapper.selectOne(any())).thenReturn(failed, job(2L, "queued"));

        assertFalse(analysisJobService.retry(1L, 7L));
        verify(analysisJobMapper, never()).update(any(), any());
    }

    @Test
    void retry_shouldRequeueFailedJob() {
        AnalysisJob failed = job(1L, "failed");
        failed.setDataFingerprint("fp");
        // The only active job with this fingerprint is the same job, which does not block a retry.
        when(analysisJobMapper.selectOne(any())).thenReturn(failed, failed);
        when(analysisJobMapper.update(isNull(), any())).thenReturn(1);

        assertTrue(analysisJobService.retry(1L, 7L));
        assertEquals("queued", capturedEvent().getStatus());
    }

    @Test
    void retry_whenConcurrentUpdateWins_shouldReturnFalse() {
        AnalysisJob failed = job(1L, "failed");
        when(analysisJobMapper.selectOne(any())).thenReturn(failed, null);
        when(analysisJobMapper.update(isNull(), any())).thenReturn(0);

        assertFalse(analysisJobService.retry(1L, 7L));
        verifyNoInteractions(analysisJobEventMapper);
    }

    @Test
    void readMethods_shouldDelegateToMappers() {
        AnalysisJob job = job(1L, "queued");
        List<AnalysisJob> jobs = List.of(job);
        List<AnalysisJobEvent> events = List.of(new AnalysisJobEvent());
        when(analysisJobMapper.selectOne(any())).thenReturn(job);
        when(analysisJobMapper.selectById(1L)).thenReturn(job);
        when(analysisJobMapper.selectList(any())).thenReturn(jobs);
        when(analysisJobEventMapper.selectList(any())).thenReturn(events);

        assertSame(job, analysisJobService.getForUser(1L, 7L));
        assertSame(job, analysisJobService.getById(1L));
        assertSame(jobs, analysisJobService.listForCharts(7L, List.of(2L)));
        assertSame(events, analysisJobService.listEvents(1L));
    }

    @Test
    void listForCharts_withNoChartIds_shouldSkipQuery() {
        assertTrue(analysisJobService.listForCharts(7L, null).isEmpty());
        assertTrue(analysisJobService.listForCharts(7L, List.of()).isEmpty());
        verifyNoInteractions(analysisJobMapper);
    }

    @Test
    void requeueAfterPersistenceFailure_shouldRequeueJobAndChart() {
        AnalysisJob running = job(1L, "running");
        running.setChartId(9L);
        when(analysisJobMapper.selectById(1L)).thenReturn(running);
        when(analysisJobMapper.update(isNull(), any())).thenReturn(1);
        when(chartService.updateById(any(Chart.class))).thenReturn(true);

        analysisJobService.requeueAfterPersistenceFailure(1L, "db down");

        ArgumentCaptor<Chart> chart = ArgumentCaptor.forClass(Chart.class);
        verify(chartService).updateById(chart.capture());
        assertEquals(9L, chart.getValue().getId());
        assertEquals("queued", chart.getValue().getStatus());
        assertEquals("queued", capturedEvent().getStatus());
    }

    @Test
    void requeueAfterPersistenceFailure_whenJobNotRunning_shouldThrow() {
        when(analysisJobMapper.selectById(1L)).thenReturn(null, job(1L, "failed"));

        assertBusinessError(ErrorCode.OPERATION_ERROR,
                () -> analysisJobService.requeueAfterPersistenceFailure(1L, "reason"));
        assertBusinessError(ErrorCode.OPERATION_ERROR,
                () -> analysisJobService.requeueAfterPersistenceFailure(1L, "reason"));
        verify(analysisJobMapper, never()).update(any(), any());
    }

    @Test
    void requeueAfterPersistenceFailure_whenJobUpdateLosesRace_shouldThrow() {
        when(analysisJobMapper.selectById(1L)).thenReturn(job(1L, "running"));
        when(analysisJobMapper.update(isNull(), any())).thenReturn(0);

        assertBusinessError(ErrorCode.OPERATION_ERROR,
                () -> analysisJobService.requeueAfterPersistenceFailure(1L, "reason"));
        verifyNoInteractions(chartService);
    }

    @Test
    void requeueAfterPersistenceFailure_whenChartUpdateFails_shouldThrowWithoutEvent() {
        when(analysisJobMapper.selectById(1L)).thenReturn(job(1L, "running"));
        when(analysisJobMapper.update(isNull(), any())).thenReturn(1);
        when(chartService.updateById(any(Chart.class))).thenReturn(false);

        assertBusinessError(ErrorCode.OPERATION_ERROR,
                () -> analysisJobService.requeueAfterPersistenceFailure(1L, "reason"));
        verifyNoInteractions(analysisJobEventMapper);
    }

    @Test
    void cancelActiveJobsForChart_shouldCancelEachJobThatIsStillActive() {
        when(analysisJobMapper.selectList(any())).thenReturn(List.of(job(1L, "running"), job(2L, "queued")));
        // Job 2 finished between the select and the update, so only job 1 is cancelled.
        when(analysisJobMapper.update(isNull(), any())).thenReturn(1, 0);

        assertEquals(1, analysisJobService.cancelActiveJobsForChart(9L));

        AnalysisJobEvent event = capturedEvent();
        assertEquals(1L, event.getJobId());
        assertEquals("cancelled", event.getStatus());
    }

    @Test
    void cancelActiveJobsForChart_withoutActiveJobs_shouldDoNothing() {
        assertEquals(0, analysisJobService.cancelActiveJobsForChart(9L));
        verify(analysisJobMapper, never()).update(any(), any());
    }

    @Test
    void recoverStaleRunning_withRetriesLeft_shouldMoveToRetrying() {
        when(analysisJobMapper.update(isNull(), any())).thenReturn(1);

        assertEquals(AnalysisJobStatusEnum.RETRYING, analysisJobService.recoverStaleRunning(1L, new Date()));

        verify(analysisJobMapper).update(isNull(), any());
        assertEquals("retrying", capturedEvent().getStatus());
    }

    @Test
    void recoverStaleRunning_withNoRetriesLeft_shouldFail() {
        when(analysisJobMapper.update(isNull(), any())).thenReturn(0, 1);

        assertEquals(AnalysisJobStatusEnum.FAILED, analysisJobService.recoverStaleRunning(1L, new Date()));

        assertEquals("failed", capturedEvent().getStatus());
    }

    @Test
    void recoverStaleRunning_whenWorkerFinishedMeanwhile_shouldDoNothing() {
        when(analysisJobMapper.update(isNull(), any())).thenReturn(0, 0);

        assertNull(analysisJobService.recoverStaleRunning(1L, new Date()));
        verifyNoInteractions(analysisJobEventMapper);
    }

    @Test
    void claimStalePending_shouldReportWhetherThisCallerWon() {
        when(analysisJobMapper.update(isNull(), any())).thenReturn(1, 0);

        assertTrue(analysisJobService.claimStalePending(1L, 600));
        assertFalse(analysisJobService.claimStalePending(1L, 600));
    }

    @Test
    void staleQueries_shouldDelegateToMapper() {
        AnalysisJob job = job(1L, "running");
        when(analysisJobMapper.selectList(any())).thenReturn(List.of(job));

        assertEquals(List.of(job), analysisJobService.listStaleRunning(new Date(), 10));
        assertEquals(List.of(job), analysisJobService.listStalePending(600, 10));
    }

    private AnalysisJobEvent capturedEvent() {
        ArgumentCaptor<AnalysisJobEvent> captor = ArgumentCaptor.forClass(AnalysisJobEvent.class);
        verify(analysisJobEventMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static AnalysisJob job(long id, String status) {
        AnalysisJob job = new AnalysisJob();
        job.setId(id);
        job.setStatus(status);
        return job;
    }

    private static void assertBusinessError(ErrorCode expected, Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        assertEquals(expected.getCode(), exception.getCode());
    }
}
