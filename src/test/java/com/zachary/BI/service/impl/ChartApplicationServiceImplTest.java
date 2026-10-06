package com.zachary.BI.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zachary.BI.BiMq.BiMessageProducer;
import com.zachary.BI.common.DeleteRequest;
import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.exception.BusinessException;
import com.zachary.BI.manager.RedisRateLimitManager;
import com.zachary.BI.model.dto.chart.ChartAddRequest;
import com.zachary.BI.model.dto.chart.ChartEditRequest;
import com.zachary.BI.model.dto.chart.ChartQueryRequest;
import com.zachary.BI.model.dto.chart.ChartUpdateRequest;
import com.zachary.BI.model.dto.chart.GenChartByAIRequest;
import com.zachary.BI.model.dto.upload.CompletedUploadFile;
import com.zachary.BI.model.entity.AnalysisJob;
import com.zachary.BI.model.entity.AnalysisJobEvent;
import com.zachary.BI.model.entity.Chart;
import com.zachary.BI.model.entity.User;
import com.zachary.BI.model.vo.BiResponse;
import com.zachary.BI.model.vo.DataQualityReport;
import com.zachary.BI.service.AnalysisJobService;
import com.zachary.BI.service.ChartService;
import com.zachary.BI.service.ResumableUploadService;
import com.zachary.BI.utils.ExcelUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChartApplicationServiceImplTest {

    private static final long USER_ID = 101L;
    private static final String TRUNCATION_NOTICE =
            "\n\n# Analysis input was truncated to fit the safe chart-data limit.\n";

    @Mock
    private ChartService chartService;
    @Mock
    private AnalysisJobService analysisJobService;
    @Mock
    private DataQualityService dataQualityService;
    @Mock
    private RedisRateLimitManager redisRateLimitManager;
    @Mock
    private BiMessageProducer biMessageProducer;
    @Mock
    private ResumableUploadService resumableUploadService;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private ChartApplicationServiceImpl chartApplicationService;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(USER_ID);
        // Run the callback directly; rollback itself is covered by AnalysisSubmissionIT against real MySQL.
        lenient().when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    // region chart CRUD

    @Nested
    class Crud {

        @Test
        void addChart_shouldSaveChartForUser() {
            ChartAddRequest request = new ChartAddRequest();
            request.setName("Sales");
            doAnswer(invocation -> {
                invocation.<Chart>getArgument(0).setId(5L);
                return true;
            }).when(chartService).save(any(Chart.class));

            assertEquals(5L, chartApplicationService.addChart(request, user));

            Chart saved = capturedSavedChart();
            assertEquals("Sales", saved.getName());
            assertEquals(USER_ID, saved.getUserId());
        }

        @Test
        void addChart_shouldRejectNullAndSurfaceSaveFailure() {
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> chartApplicationService.addChart(null, user));

            when(chartService.save(any(Chart.class))).thenReturn(false);
            assertBusinessError(ErrorCode.OPERATION_ERROR,
                    () -> chartApplicationService.addChart(new ChartAddRequest(), user));
        }

        @Test
        void deleteChart_shouldValidateRequestAndExistence() {
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> chartApplicationService.deleteChart(null, user, false));
            assertBusinessError(ErrorCode.PARAMS_ERROR,
                    () -> chartApplicationService.deleteChart(deleteRequest(0L), user, false));
            assertBusinessError(ErrorCode.NOT_FOUND_ERROR,
                    () -> chartApplicationService.deleteChart(deleteRequest(8L), user, false));
        }

        @Test
        void deleteChart_shouldOnlyAllowOwnerOrAdmin() {
            when(chartService.getById(8L)).thenReturn(chart(8L, 999L));
            when(chartService.removeById(8L)).thenReturn(true);

            assertBusinessError(ErrorCode.NO_AUTH_ERROR,
                    () -> chartApplicationService.deleteChart(deleteRequest(8L), user, false));
            assertTrue(chartApplicationService.deleteChart(deleteRequest(8L), user, true));
        }

        @Test
        void deleteChart_shouldAllowOwnerAndCancelTheChartsActiveJobs() {
            when(chartService.getById(8L)).thenReturn(chart(8L, USER_ID));
            when(chartService.removeById(8L)).thenReturn(true);
            when(analysisJobService.cancelActiveJobsForChart(8L)).thenReturn(1);

            assertTrue(chartApplicationService.deleteChart(deleteRequest(8L), user, false));
            verify(analysisJobService).cancelActiveJobsForChart(8L);
        }

        @Test
        void deleteChart_whenNotAuthorised_shouldNotCancelJobs() {
            when(chartService.getById(8L)).thenReturn(chart(8L, 999L));

            assertBusinessError(ErrorCode.NO_AUTH_ERROR,
                    () -> chartApplicationService.deleteChart(deleteRequest(8L), user, false));
            verify(analysisJobService, never()).cancelActiveJobsForChart(anyLong());
        }

        @Test
        void updateChartAsAdmin_shouldValidateAndUpdate() {
            ChartUpdateRequest request = new ChartUpdateRequest();
            request.setId(0L);
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> chartApplicationService.updateChartAsAdmin(null));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> chartApplicationService.updateChartAsAdmin(request));

            request.setId(8L);
            assertBusinessError(ErrorCode.NOT_FOUND_ERROR, () -> chartApplicationService.updateChartAsAdmin(request));

            request.setGenResult("new result");
            when(chartService.getById(8L)).thenReturn(chart(8L, 999L));
            when(chartService.updateById(any(Chart.class))).thenReturn(true);
            assertTrue(chartApplicationService.updateChartAsAdmin(request));
            verify(chartService).updateById(argThat((Chart chart) -> "new result".equals(chart.getGenResult())));
        }

        @Test
        void getChart_shouldValidateIdAndReturnChart() {
            Chart chart = chart(8L, USER_ID);
            when(chartService.getById(8L)).thenReturn(chart);

            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> chartApplicationService.getChart(0));
            assertSame(chart, chartApplicationService.getChart(8L));
        }

        @Test
        void editChart_shouldEnforceOwnership() {
            ChartEditRequest request = new ChartEditRequest();
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> chartApplicationService.editChart(null, user, false));
            request.setId(0L);
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> chartApplicationService.editChart(request, user, false));

            request.setId(8L);
            request.setName("Renamed");
            when(chartService.getById(8L)).thenReturn(chart(8L, 999L));
            when(chartService.updateById(any(Chart.class))).thenReturn(true);

            assertBusinessError(ErrorCode.NO_AUTH_ERROR, () -> chartApplicationService.editChart(request, user, false));
            assertTrue(chartApplicationService.editChart(request, user, true));
            verify(chartService).updateById(argThat((Chart chart) -> "Renamed".equals(chart.getName())));
        }

        @Test
        void editChart_shouldAllowOwner() {
            ChartEditRequest request = new ChartEditRequest();
            request.setId(8L);
            when(chartService.getById(8L)).thenReturn(chart(8L, USER_ID));
            when(chartService.updateById(any(Chart.class))).thenReturn(true);

            assertTrue(chartApplicationService.editChart(request, user, false));
        }
    }

    // endregion

    // region listing and query construction

    @Nested
    class Listing {

        @Test
        void listCharts_shouldRejectNullAndUsePagination() {
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> chartApplicationService.listCharts(null));

            ChartQueryRequest request = new ChartQueryRequest();
            request.setCurrent(3);
            request.setPageSize(50);
            Page<Chart> expected = new Page<>();
            when(chartService.page(any(Page.class), any(QueryWrapper.class))).thenReturn(expected);

            assertSame(expected, chartApplicationService.listCharts(request));

            ArgumentCaptor<Page<Chart>> page = ArgumentCaptor.forClass(Page.class);
            verify(chartService).page(page.capture(), any(QueryWrapper.class));
            assertEquals(3, page.getValue().getCurrent());
            assertEquals(50, page.getValue().getSize());
        }

        @Test
        void listPublicCharts_shouldCapPageSize() {
            ChartQueryRequest request = new ChartQueryRequest();
            request.setPageSize(21);

            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> chartApplicationService.listPublicCharts(null));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> chartApplicationService.listPublicCharts(request));

            request.setPageSize(20);
            chartApplicationService.listPublicCharts(request);
            verify(chartService).page(any(Page.class), any(QueryWrapper.class));
        }

        @Test
        void listMyCharts_shouldForceCurrentUserFilter() {
            ChartQueryRequest request = new ChartQueryRequest();
            request.setUserId(999L);

            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> chartApplicationService.listMyCharts(null, user));
            ChartQueryRequest tooLarge = new ChartQueryRequest();
            tooLarge.setPageSize(21);
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> chartApplicationService.listMyCharts(tooLarge, user));

            chartApplicationService.listMyCharts(request, user);

            QueryWrapper<Chart> wrapper = capturedQueryWrapper();
            assertTrue(wrapper.getCustomSqlSegment().contains("userId"));
            assertTrue(wrapper.getParamNameValuePairs().containsValue(USER_ID));
            assertFalse(wrapper.getParamNameValuePairs().containsValue(999L));
        }

        @Test
        void listCharts_shouldTranslateEveryAdvancedFilter() {
            ChartQueryRequest request = new ChartQueryRequest();
            request.setKeyWord("  revenue ");
            request.setChartTypes(Arrays.asList(" bar ", "", null, "bar", "line"));
            request.setStatuses(List.of("Queued", "completed", "analysing", "FAILED"));
            request.setSourceFileType("csv");
            request.setMinSourceFileSize(10L);
            request.setMaxSourceFileSize(100L);
            request.setGeneratedFrom(LocalDate.of(2026, 1, 1));
            request.setGeneratedTo(LocalDate.of(2026, 1, 31));
            request.setId(4L);
            request.setGoal("growth");
            request.setName("Sales");
            request.setChartType("pie");
            request.setUserId(USER_ID);
            request.setSortField("createTime");
            request.setSortOrder("ascend");

            chartApplicationService.listCharts(request);

            QueryWrapper<Chart> wrapper = capturedQueryWrapper();
            String sql = wrapper.getCustomSqlSegment();
            for (String column : List.of("name", "sourceFileName", "chartData", "chartType", "status",
                    "sourceFileType", "sourceFileSize", "generatedAt", "id", "goal", "userId")) {
                assertTrue(sql.contains(column), () -> "missing " + column + " in " + sql);
            }
            assertTrue(sql.contains("ORDER BY createTime ASC"), sql);

            Collection<Object> values = wrapper.getParamNameValuePairs().values();
            assertTrue(values.contains("%revenue%"));
            // Legacy status aliases are expanded to every stored value they represent.
            for (String status : List.of("queued", "wait", "succeeded", "succeed", "running", "failed")) {
                assertTrue(values.contains(status), () -> "missing status " + status + " in " + values);
            }
            assertTrue(values.contains(LocalDate.of(2026, 1, 1).atStartOfDay()));
            // The end date is inclusive, so the exclusive bound is the following midnight.
            assertTrue(values.contains(LocalDate.of(2026, 2, 1).atStartOfDay()));
            assertEquals(1, values.stream().filter("bar"::equals).count());
        }

        @Test
        void listCharts_withEmptyRequest_shouldApplyNoFilters() {
            ChartQueryRequest request = new ChartQueryRequest();
            request.setSortOrder(null);

            chartApplicationService.listCharts(request);

            // No filters; only the id tie-breaker that keeps pagination deterministic.
            assertEquals("ORDER BY id DESC", capturedQueryWrapper().getCustomSqlSegment().trim());
        }

        @Test
        void listCharts_shouldRejectInvalidAdvancedFilters() {
            ChartQueryRequest longKeyword = new ChartQueryRequest();
            longKeyword.setKeyWord("k".repeat(101));

            ChartQueryRequest negativeMin = new ChartQueryRequest();
            negativeMin.setMinSourceFileSize(-1L);

            ChartQueryRequest negativeMax = new ChartQueryRequest();
            negativeMax.setMaxSourceFileSize(-1L);

            ChartQueryRequest invertedSize = new ChartQueryRequest();
            invertedSize.setMinSourceFileSize(10L);
            invertedSize.setMaxSourceFileSize(5L);

            ChartQueryRequest invertedDates = new ChartQueryRequest();
            invertedDates.setGeneratedFrom(LocalDate.of(2026, 2, 1));
            invertedDates.setGeneratedTo(LocalDate.of(2026, 1, 1));

            for (ChartQueryRequest request : List.of(longKeyword, negativeMin, negativeMax, invertedSize, invertedDates)) {
                assertBusinessError(ErrorCode.PARAMS_ERROR, () -> chartApplicationService.listCharts(request));
            }
            verifyNoInteractions(chartService);
        }
    }

    // endregion

    // region AI generation from multipart files

    @Nested
    class MultipartGeneration {

        @Test
        void generateChart_shouldRejectInvalidFiles() {
            MockMultipartFile empty = new MockMultipartFile("file", "a.csv", "text/csv", new byte[0]);
            MockMultipartFile tooLarge = new MockMultipartFile("file", "a.csv", "text/csv", new byte[1024 * 1024 + 1]);
            MockMultipartFile wrongType = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());

            assertBusinessError(ErrorCode.PARAMS_ERROR,
                    () -> chartApplicationService.generateChart((MockMultipartFile) null, validRequest(), user));
            assertBusinessError(ErrorCode.PARAMS_ERROR,
                    () -> chartApplicationService.generateChart(empty, validRequest(), user));
            assertBusinessError(ErrorCode.SYSTEM_ERROR,
                    () -> chartApplicationService.generateChart(tooLarge, validRequest(), user));
            assertBusinessError(ErrorCode.SYSTEM_ERROR,
                    () -> chartApplicationService.generateChart(wrongType, validRequest(), user));
            verifyNoInteractions(dataQualityService, chartService);
        }

        @Test
        void generateChart_shouldQueueNewAnalysisJob() throws Exception {
            MockMultipartFile file = csvFile("Sales.CSV", "month,sales\nJan,10\n");
            when(dataQualityService.inspect(any(ExcelUtils.Spreadsheet.class))).thenReturn(report(false));
            when(analysisJobService.findActiveJob(eq(USER_ID), anyString())).thenReturn(Optional.empty());
            stubChartSave(20L);
            when(analysisJobService.create(eq(20L), eq(USER_ID), anyString())).thenReturn(job(30L, 20L));

            BiResponse response = chartApplicationService.generateChart(file, validRequest(), user);

            assertEquals(20L, response.getChartId());
            assertEquals(30L, response.getJobId());
            assertFalse(response.isReused());
            verify(redisRateLimitManager).doRateLimit("chart:generate:" + USER_ID);
            verify(biMessageProducer).sendMessage(30L);

            Chart saved = capturedSavedChart();
            assertEquals("Sales.CSV", saved.getSourceFileName());
            assertEquals("csv", saved.getSourceFileType());
            assertEquals(file.getSize(), saved.getSourceFileSize());
            assertEquals("month,sales\nJan,10\n", saved.getChartData());
            assertEquals("queued", saved.getStatus());
            assertEquals("Revenue", saved.getName());
            assertEquals("Show the trend", saved.getGoal());
            assertEquals("line", saved.getChartType());
            assertEquals(USER_ID, saved.getUserId());
        }

        @Test
        void generateChart_shouldRequireValidRequestFields() throws Exception {
            MockMultipartFile file = csvFile("a.csv", "a\n1\n");
            when(dataQualityService.inspect(any(ExcelUtils.Spreadsheet.class))).thenReturn(report(false));
            GenChartByAIRequest blankGoal = validRequest();
            blankGoal.setGoal(" ");
            GenChartByAIRequest blankName = validRequest();
            blankName.setName("");

            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> chartApplicationService.generateChart(file, null, user));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> chartApplicationService.generateChart(file, blankGoal, user));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> chartApplicationService.generateChart(file, blankName, user));
            verifyNoInteractions(analysisJobService);
        }

        @Test
        void generateChart_shouldRequireAcknowledgementOfQualityIssues() throws Exception {
            MockMultipartFile file = csvFile("a.csv", "a\n1\n");
            when(dataQualityService.inspect(any(ExcelUtils.Spreadsheet.class))).thenReturn(report(true));

            assertBusinessError(ErrorCode.PARAMS_ERROR,
                    () -> chartApplicationService.generateChart(file, validRequest(), user));
            verifyNoInteractions(analysisJobService);
        }

        @Test
        void generateChart_withAcknowledgedQualityIssues_shouldProceed() throws Exception {
            MockMultipartFile file = csvFile("a.csv", "a\n1\n");
            GenChartByAIRequest request = validRequest();
            request.setQualityAcknowledged(true);
            when(dataQualityService.inspect(any(ExcelUtils.Spreadsheet.class))).thenReturn(report(true));
            when(analysisJobService.findActiveJob(eq(USER_ID), anyString())).thenReturn(Optional.of(job(30L, 20L)));

            assertTrue(chartApplicationService.generateChart(file, request, user).isReused());
        }

        @Test
        void generateChart_whenSameAnalysisIsActive_shouldReuseItWithoutRateLimiting() throws Exception {
            MockMultipartFile file = csvFile("a.csv", "a\n1\n");
            when(dataQualityService.inspect(any(ExcelUtils.Spreadsheet.class))).thenReturn(report(false));
            when(analysisJobService.findActiveJob(eq(USER_ID), anyString())).thenReturn(Optional.of(job(30L, 20L)));

            BiResponse response = chartApplicationService.generateChart(file, validRequest(), user);

            assertTrue(response.isReused());
            assertEquals(20L, response.getChartId());
            assertEquals(30L, response.getJobId());
            verifyNoInteractions(redisRateLimitManager, chartService, biMessageProducer);
        }

        @Test
        void generateChart_whenChartSaveFails_shouldThrowSystemError() throws Exception {
            MockMultipartFile file = csvFile("a.csv", "a\n1\n");
            when(dataQualityService.inspect(any(ExcelUtils.Spreadsheet.class))).thenReturn(report(false));
            when(analysisJobService.findActiveJob(eq(USER_ID), anyString())).thenReturn(Optional.empty());
            when(chartService.save(any(Chart.class))).thenReturn(false);

            assertBusinessError(ErrorCode.SYSTEM_ERROR,
                    () -> chartApplicationService.generateChart(file, validRequest(), user));
            verify(analysisJobService, never()).create(anyLong(), anyLong(), anyString());
        }

        @Test
        void generateChart_whenConcurrentDuplicateWins_shouldDiscardChartAndReuseWinner() throws Exception {
            MockMultipartFile file = csvFile("a.csv", "a\n1\n");
            when(dataQualityService.inspect(any(ExcelUtils.Spreadsheet.class))).thenReturn(report(false));
            when(analysisJobService.findActiveJob(eq(USER_ID), anyString()))
                    .thenReturn(Optional.empty(), Optional.of(job(31L, 21L)));
            stubChartSave(20L);
            when(analysisJobService.create(eq(20L), eq(USER_ID), anyString()))
                    .thenThrow(new DuplicateKeyException("duplicate"));

            BiResponse response = chartApplicationService.generateChart(file, validRequest(), user);

            assertTrue(response.isReused());
            assertEquals(31L, response.getJobId());
            // The transaction rollback removes this request's chart; no manual delete is needed.
            verify(chartService, never()).removeById(anyLong());
            verifyNoInteractions(biMessageProducer);
        }

        @Test
        void generateChart_whenDuplicateWinnerFinishedMeanwhile_shouldRetryAndQueueNewJob() throws Exception {
            MockMultipartFile file = csvFile("a.csv", "a\n1\n");
            when(dataQualityService.inspect(any(ExcelUtils.Spreadsheet.class))).thenReturn(report(false));
            when(analysisJobService.findActiveJob(eq(USER_ID), anyString())).thenReturn(Optional.empty());
            stubChartSave(20L);
            when(analysisJobService.create(eq(20L), eq(USER_ID), anyString()))
                    .thenThrow(new DuplicateKeyException("duplicate"))
                    .thenReturn(job(30L, 20L));

            BiResponse response = chartApplicationService.generateChart(file, validRequest(), user);

            assertFalse(response.isReused());
            assertEquals(30L, response.getJobId());
            verify(transactionTemplate, times(2)).execute(any());
            verify(biMessageProducer).sendMessage(30L);
        }

        @Test
        void generateChart_whenDuplicatePersistsWithoutWinner_shouldAskUserToRetry() throws Exception {
            MockMultipartFile file = csvFile("a.csv", "a\n1\n");
            when(dataQualityService.inspect(any(ExcelUtils.Spreadsheet.class))).thenReturn(report(false));
            when(analysisJobService.findActiveJob(eq(USER_ID), anyString())).thenReturn(Optional.empty());
            stubChartSave(20L);
            when(analysisJobService.create(eq(20L), eq(USER_ID), anyString()))
                    .thenThrow(new DuplicateKeyException("duplicate"));

            assertBusinessError(ErrorCode.OPERATION_ERROR,
                    () -> chartApplicationService.generateChart(file, validRequest(), user));
            verify(transactionTemplate, times(2)).execute(any());
            verifyNoInteractions(biMessageProducer);
        }

        @Test
        void generateChart_whenQueueUnavailable_shouldFailJobAndChart() throws Exception {
            MockMultipartFile file = csvFile("a.csv", "a\n1\n");
            when(dataQualityService.inspect(any(ExcelUtils.Spreadsheet.class))).thenReturn(report(false));
            when(analysisJobService.findActiveJob(eq(USER_ID), anyString())).thenReturn(Optional.empty());
            stubChartSave(20L);
            when(analysisJobService.create(eq(20L), eq(USER_ID), anyString())).thenReturn(job(30L, 20L));
            doThrow(new IllegalStateException("broker down")).when(biMessageProducer).sendMessage(30L);
            when(analysisJobService.failQueued(eq(30L), anyString())).thenReturn(true);

            assertBusinessError(ErrorCode.SYSTEM_ERROR,
                    () -> chartApplicationService.generateChart(file, validRequest(), user));

            verify(analysisJobService).failQueued(eq(30L), argThat(reason -> reason.contains("broker down")));
            verify(chartService).updateById(argThat((Chart chart) ->
                    chart.getId() == 20L && "failed".equals(chart.getStatus())));
        }

        @Test
        void generateChart_whenPublishFailureIsAmbiguousButWorkerStarted_shouldKeepJobAndSucceed() throws Exception {
            MockMultipartFile file = csvFile("a.csv", "a\n1\n");
            when(dataQualityService.inspect(any(ExcelUtils.Spreadsheet.class))).thenReturn(report(false));
            when(analysisJobService.findActiveJob(eq(USER_ID), anyString())).thenReturn(Optional.empty());
            stubChartSave(20L);
            when(analysisJobService.create(eq(20L), eq(USER_ID), anyString())).thenReturn(job(30L, 20L));
            // e.g. the confirm timed out, yet the broker delivered the message and a worker moved the job to running.
            doThrow(new IllegalStateException("confirm timed out")).when(biMessageProducer).sendMessage(30L);
            when(analysisJobService.failQueued(eq(30L), anyString())).thenReturn(false);

            BiResponse response = chartApplicationService.generateChart(file, validRequest(), user);

            assertEquals(30L, response.getJobId());
            verify(analysisJobService, never()).fail(anyLong(), anyString());
            verify(chartService, never()).updateById(any(Chart.class));
        }

        @Test
        void inspectData_shouldValidateThenInspect() throws Exception {
            MockMultipartFile file = csvFile("a.csv", "a\n1\n");
            DataQualityReport report = report(false);
            when(dataQualityService.inspect(file)).thenReturn(report);

            assertSame(report, chartApplicationService.inspectData(file));
            assertBusinessError(ErrorCode.PARAMS_ERROR,
                    () -> chartApplicationService.inspectData((MockMultipartFile) null));
        }
    }

    // endregion

    // region AI generation from completed resumable uploads

    @Nested
    class TokenGeneration {

        @TempDir
        Path tempDir;

        @Test
        void generateChart_shouldUseCompletedUploadAndItsHashAsIdentity() throws Exception {
            CompletedUploadFile upload = completedUpload("month,sales\nJan,10\n", "csv", 100L);
            when(resumableUploadService.resolveCompletedUpload("token", user)).thenReturn(upload);
            when(dataQualityService.inspect(any(ExcelUtils.Spreadsheet.class))).thenReturn(report(false));
            when(analysisJobService.findActiveJob(eq(USER_ID), anyString())).thenReturn(Optional.empty());
            stubChartSave(20L);
            when(analysisJobService.create(eq(20L), eq(USER_ID), anyString())).thenReturn(job(30L, 20L));

            BiResponse response = chartApplicationService.generateChart("token", validRequest(), user);

            assertEquals(30L, response.getJobId());
            Chart saved = capturedSavedChart();
            assertEquals("upload.csv", saved.getSourceFileName());
            assertEquals(100L, saved.getSourceFileSize());
            assertEquals("month,sales\nJan,10\n", saved.getChartData());
        }

        @Test
        void generateChart_sameFileAndRequest_shouldProduceStableFingerprint() throws Exception {
            CompletedUploadFile upload = completedUpload("a\n1\n", "csv", 4L);
            when(resumableUploadService.resolveCompletedUpload("token", user)).thenReturn(upload);
            when(dataQualityService.inspect(any(ExcelUtils.Spreadsheet.class))).thenReturn(report(false));
            List<String> fingerprints = new ArrayList<>();
            when(analysisJobService.findActiveJob(eq(USER_ID), anyString())).thenAnswer(invocation -> {
                fingerprints.add(invocation.getArgument(1));
                return Optional.of(job(30L, 20L));
            });
            GenChartByAIRequest otherType = validRequest();
            otherType.setChartType(null);

            chartApplicationService.generateChart("token", validRequest(), user);
            chartApplicationService.generateChart("token", validRequest(), user);
            chartApplicationService.generateChart("token", otherType, user);

            assertEquals(fingerprints.get(0), fingerprints.get(1));
            assertNotEquals(fingerprints.get(0), fingerprints.get(2));
            assertTrue(fingerprints.get(0).matches("[0-9a-f]{64}"));
        }

        @Test
        void generateChart_withLargeData_shouldStoreUtf8SafeTruncatedPreview() throws Exception {
            // Mix 1-, 2-, 3- and 4-byte UTF-8 code points so truncation must respect every boundary.
            StringBuilder csv = new StringBuilder("label\n");
            while (csv.length() < 80_000) {
                csv.append("aé中😀\n");
            }
            CompletedUploadFile upload = completedUpload(csv.toString(), "csv", 200_000L);
            when(resumableUploadService.resolveCompletedUpload("token", user)).thenReturn(upload);
            when(dataQualityService.inspect(any(ExcelUtils.Spreadsheet.class))).thenReturn(report(false));
            when(analysisJobService.findActiveJob(eq(USER_ID), anyString())).thenReturn(Optional.empty());
            stubChartSave(20L);
            when(analysisJobService.create(eq(20L), eq(USER_ID), anyString())).thenReturn(job(30L, 20L));

            chartApplicationService.generateChart("token", validRequest(), user);

            String chartData = capturedSavedChart().getChartData();
            assertTrue(chartData.startsWith("label\naé中😀\n"));
            assertTrue(chartData.endsWith(TRUNCATION_NOTICE));
            assertTrue(chartData.getBytes(StandardCharsets.UTF_8).length <= 60 * 1024);
            String preview = chartData.substring(0, chartData.length() - TRUNCATION_NOTICE.length());
            assertFalse(Character.isHighSurrogate(preview.charAt(preview.length() - 1)),
                    "Truncation must not split a surrogate pair");
        }

        @Test
        void generateChart_shouldRejectOversizedOrUnsupportedUploads() throws Exception {
            CompletedUploadFile oversized = completedUpload("a\n1\n", "csv", 5 * 1024 * 1024L + 1);
            CompletedUploadFile unsupported = completedUpload("a\n1\n", "txt", 4L);
            when(resumableUploadService.resolveCompletedUpload("big", user)).thenReturn(oversized);
            when(resumableUploadService.resolveCompletedUpload("txt", user)).thenReturn(unsupported);

            assertBusinessError(ErrorCode.PARAMS_ERROR,
                    () -> chartApplicationService.generateChart("big", validRequest(), user));
            assertBusinessError(ErrorCode.PARAMS_ERROR,
                    () -> chartApplicationService.inspectData("txt", user));
            verifyNoInteractions(dataQualityService);
        }

        @Test
        void inspectData_shouldInspectCompletedUpload() throws Exception {
            CompletedUploadFile upload = completedUpload("a\n1\n", "csv", 4L);
            DataQualityReport report = report(false);
            when(resumableUploadService.resolveCompletedUpload("token", user)).thenReturn(upload);
            when(dataQualityService.inspect(upload.path(), "csv")).thenReturn(report);

            assertSame(report, chartApplicationService.inspectData("token", user));
        }

        private CompletedUploadFile completedUpload(String content, String type, long declaredSize) throws Exception {
            Path path = Files.createTempFile(tempDir, "completed-", ".data");
            Files.writeString(path, content, StandardCharsets.UTF_8);
            return new CompletedUploadFile(path, "upload." + type, type, declaredSize, "a".repeat(64));
        }
    }

    // endregion

    // region job operations

    @Nested
    class JobOperations {

        @Test
        void listJobs_shouldCapChartIds() {
            List<Long> tooMany = LongStream.rangeClosed(1, 21).boxed().toList();
            List<AnalysisJob> jobs = List.of(job(1L, 2L));
            when(analysisJobService.listForCharts(USER_ID, null)).thenReturn(jobs);

            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> chartApplicationService.listJobs(tooMany, user));
            assertSame(jobs, chartApplicationService.listJobs(null, user));
        }

        @Test
        void listJobEvents_shouldRequireOwnership() {
            List<AnalysisJobEvent> events = List.of(new AnalysisJobEvent());
            when(analysisJobService.getForUser(1L, USER_ID)).thenReturn(null, job(1L, 2L));
            when(analysisJobService.listEvents(1L)).thenReturn(events);

            assertBusinessError(ErrorCode.NOT_FOUND_ERROR, () -> chartApplicationService.listJobEvents(1L, user));
            assertSame(events, chartApplicationService.listJobEvents(1L, user));
        }

        @Test
        void retryJob_whenJobCannotBeRetried_shouldThrow() {
            when(analysisJobService.retry(1L, USER_ID)).thenReturn(false);

            assertBusinessError(ErrorCode.OPERATION_ERROR, () -> chartApplicationService.retryJob(1L, user));
            verifyNoInteractions(biMessageProducer);
        }

        @Test
        void retryJob_shouldRequeueChartAndSendMessage() {
            when(analysisJobService.retry(1L, USER_ID)).thenReturn(true);
            when(analysisJobService.getForUser(1L, USER_ID)).thenReturn(job(1L, 2L));

            assertTrue(chartApplicationService.retryJob(1L, user));

            verify(chartService).updateById(argThat((Chart chart) ->
                    chart.getId() == 2L && "queued".equals(chart.getStatus())));
            verify(biMessageProducer).sendMessage(1L);
        }

        @Test
        void retryJob_whenQueueUnavailable_shouldFailJobAndChart() {
            when(analysisJobService.retry(1L, USER_ID)).thenReturn(true);
            when(analysisJobService.getForUser(1L, USER_ID)).thenReturn(job(1L, 2L));
            doThrow(new IllegalStateException("broker down")).when(biMessageProducer).sendMessage(1L);
            when(analysisJobService.failQueued(eq(1L), anyString())).thenReturn(true);

            assertBusinessError(ErrorCode.SYSTEM_ERROR, () -> chartApplicationService.retryJob(1L, user));

            verify(analysisJobService).failQueued(eq(1L), argThat(reason -> reason.contains("broker down")));
            verify(chartService).updateById(argThat((Chart chart) -> "failed".equals(chart.getStatus())));
        }

        @Test
        void retryJob_whenPublishFailureIsAmbiguousButWorkerStarted_shouldReportSuccess() {
            when(analysisJobService.retry(1L, USER_ID)).thenReturn(true);
            when(analysisJobService.getForUser(1L, USER_ID)).thenReturn(job(1L, 2L));
            doThrow(new IllegalStateException("channel closed")).when(biMessageProducer).sendMessage(1L);
            when(analysisJobService.failQueued(eq(1L), anyString())).thenReturn(false);

            assertTrue(chartApplicationService.retryJob(1L, user));

            verify(chartService, never()).updateById(argThat((Chart chart) -> "failed".equals(chart.getStatus())));
        }

        @Test
        void cancelJob_shouldValidateThenCancelChart() {
            when(analysisJobService.getForUser(1L, USER_ID)).thenReturn(null, job(1L, 2L), job(1L, 2L));
            when(analysisJobService.cancel(1L, USER_ID)).thenReturn(false, true);

            assertBusinessError(ErrorCode.NOT_FOUND_ERROR, () -> chartApplicationService.cancelJob(1L, user));
            assertBusinessError(ErrorCode.OPERATION_ERROR, () -> chartApplicationService.cancelJob(1L, user));
            assertTrue(chartApplicationService.cancelJob(1L, user));

            verify(chartService).updateById(argThat((Chart chart) ->
                    chart.getId() == 2L && "cancelled".equals(chart.getStatus())));
        }
    }

    // endregion

    private void stubChartSave(long chartId) {
        doAnswer(invocation -> {
            invocation.<Chart>getArgument(0).setId(chartId);
            return true;
        }).when(chartService).save(any(Chart.class));
    }

    private Chart capturedSavedChart() {
        ArgumentCaptor<Chart> captor = ArgumentCaptor.forClass(Chart.class);
        verify(chartService).save(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private QueryWrapper<Chart> capturedQueryWrapper() {
        ArgumentCaptor<QueryWrapper<Chart>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(chartService).page(any(Page.class), captor.capture());
        return captor.getValue();
    }

    private static MockMultipartFile csvFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private static GenChartByAIRequest validRequest() {
        GenChartByAIRequest request = new GenChartByAIRequest();
        request.setName("Revenue");
        request.setGoal("Show the trend");
        request.setChartType("line");
        return request;
    }

    private static DataQualityReport report(boolean confirmationRequired) {
        DataQualityReport report = new DataQualityReport();
        report.setConfirmationRequired(confirmationRequired);
        return report;
    }

    private static AnalysisJob job(long jobId, long chartId) {
        AnalysisJob job = new AnalysisJob();
        job.setId(jobId);
        job.setChartId(chartId);
        return job;
    }

    private static Chart chart(long id, long ownerId) {
        Chart chart = new Chart();
        chart.setId(id);
        chart.setUserId(ownerId);
        return chart;
    }

    private static DeleteRequest deleteRequest(long id) {
        DeleteRequest request = new DeleteRequest();
        request.setId(id);
        return request;
    }

    private static void assertBusinessError(ErrorCode expected, Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        assertEquals(expected.getCode(), exception.getCode());
    }
}
