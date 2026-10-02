package com.zachary.BI.service.impl;

import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zachary.BI.BiMq.BiMessageProducer;
import com.zachary.BI.common.DeleteRequest;
import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.constant.CommonConstant;
import com.zachary.BI.exception.BusinessException;
import com.zachary.BI.exception.ThrowUtils;
import com.zachary.BI.manager.RedisRateLimitManager;
import com.zachary.BI.model.dto.upload.CompletedUploadFile;
import com.zachary.BI.model.dto.chart.ChartAddRequest;
import com.zachary.BI.model.dto.chart.ChartEditRequest;
import com.zachary.BI.model.dto.chart.ChartQueryRequest;
import com.zachary.BI.model.dto.chart.ChartUpdateRequest;
import com.zachary.BI.model.dto.chart.GenChartByAIRequest;
import com.zachary.BI.model.entity.AnalysisJob;
import com.zachary.BI.model.entity.AnalysisJobEvent;
import com.zachary.BI.model.entity.Chart;
import com.zachary.BI.model.entity.User;
import com.zachary.BI.model.enums.AnalysisJobStatusEnum;
import com.zachary.BI.model.vo.BiResponse;
import com.zachary.BI.model.vo.DataQualityReport;
import com.zachary.BI.service.AnalysisJobService;
import com.zachary.BI.service.ChartApplicationService;
import com.zachary.BI.service.ChartService;
import com.zachary.BI.service.ResumableUploadService;
import com.zachary.BI.utils.ExcelUtils;
import com.zachary.BI.utils.SqlUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Owns chart use cases: CRUD authorization, query construction, data-quality gating,
 * reliable job submission, and user initiated job operations.
 */
@Service
@Slf4j
public class ChartApplicationServiceImpl implements ChartApplicationService {
    private static final long MAX_PAGE_SIZE = 20;
    private static final long MAX_MULTIPART_UPLOAD_BYTES = 1024 * 1024L;
    private static final long MAX_ANALYSIS_SOURCE_BYTES = 5 * 1024 * 1024L;
    private static final long MAX_CHART_DATA_BYTES = 60 * 1024L;
    private static final String CHART_DATA_TRUNCATION_NOTICE =
            "\n\n# Analysis input was truncated to fit the safe chart-data limit.\n";

    @Resource
    private ChartService chartService;
    @Resource
    private AnalysisJobService analysisJobService;
    @Resource
    private DataQualityService dataQualityService;
    @Resource
    private RedisRateLimitManager redisRateLimitManager;
    @Resource
    private BiMessageProducer biMessageProducer;
    @Resource
    private ResumableUploadService resumableUploadService;

    @Override
    public long addChart(ChartAddRequest request, User user) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        Chart chart = new Chart();
        BeanUtils.copyProperties(request, chart);
        chart.setUserId(user.getId());
        ThrowUtils.throwIf(!chartService.save(chart), ErrorCode.OPERATION_ERROR);
        return chart.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteChart(DeleteRequest request, User user, boolean isAdmin) {
        ThrowUtils.throwIf(request == null || request.getId() <= 0, ErrorCode.PARAMS_ERROR);
        Chart chart = requireChart(request.getId());
        ThrowUtils.throwIf(!isAdmin && !chart.getUserId().equals(user.getId()), ErrorCode.NO_AUTH_ERROR);
        // Without this, a pending job would still call the AI, fail to save into the deleted chart, and retry until
        // its attempts ran out, paying for every attempt.
        int cancelled = analysisJobService.cancelActiveJobsForChart(chart.getId());
        if (cancelled > 0) {
            log.info("Cancelled {} active analysis job(s) of deleted chart {}", cancelled, chart.getId());
        }
        return chartService.removeById(chart.getId());
    }

    @Override
    public boolean updateChartAsAdmin(ChartUpdateRequest request) {
        ThrowUtils.throwIf(request == null || request.getId() <= 0, ErrorCode.PARAMS_ERROR);
        requireChart(request.getId());
        Chart chart = new Chart();
        BeanUtils.copyProperties(request, chart);
        return chartService.updateById(chart);
    }

    @Override
    public Chart getChart(long chartId) {
        ThrowUtils.throwIf(chartId <= 0, ErrorCode.PARAMS_ERROR);
        return requireChart(chartId);
    }

    @Override
    public Page<Chart> listCharts(ChartQueryRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return chartService.page(new Page<>(request.getCurrent(), request.getPageSize()), queryWrapper(request));
    }

    @Override
    public Page<Chart> listPublicCharts(ChartQueryRequest request) {
        ThrowUtils.throwIf(request == null || request.getPageSize() > MAX_PAGE_SIZE, ErrorCode.PARAMS_ERROR);
        return chartService.page(new Page<>(request.getCurrent(), request.getPageSize()), queryWrapper(request));
    }

    @Override
    public Page<Chart> listMyCharts(ChartQueryRequest request, User user) {
        ThrowUtils.throwIf(request == null || request.getPageSize() > MAX_PAGE_SIZE, ErrorCode.PARAMS_ERROR);
        request.setUserId(user.getId());
        return chartService.page(new Page<>(request.getCurrent(), request.getPageSize()), queryWrapper(request));
    }

    @Override
    public boolean editChart(ChartEditRequest request, User user, boolean isAdmin) {
        ThrowUtils.throwIf(request == null || request.getId() <= 0, ErrorCode.PARAMS_ERROR);
        Chart oldChart = requireChart(request.getId());
        ThrowUtils.throwIf(!isAdmin && !oldChart.getUserId().equals(user.getId()), ErrorCode.NO_AUTH_ERROR);
        Chart chart = new Chart();
        BeanUtils.copyProperties(request, chart);
        return chartService.updateById(chart);
    }

    @Override
    public BiResponse generateChart(MultipartFile file, GenChartByAIRequest request, User user) throws Exception {
        validateDataFile(file);

        DataQualityReport qualityReport = dataQualityService.inspect(file);
        String csv = ExcelUtils.excelToCsv(file);
        String originalFileName = StringUtils.defaultString(file.getOriginalFilename());
        String sourceFileType = FileUtil.getSuffix(originalFileName).toLowerCase(Locale.ROOT);
        return submitAnalysis(
                request,
                user,
                qualityReport,
                csv,
                originalFileName,
                sourceFileType,
                file.getSize(),
                csv
        );
    }

    @Override
    public BiResponse generateChart(String fileToken, GenChartByAIRequest request, User user) throws Exception {
        CompletedUploadFile completedUpload = resumableUploadService.resolveCompletedUpload(fileToken, user);
        validateCompletedUploadForAnalysis(completedUpload);

        DataQualityReport qualityReport = dataQualityService.inspect(
                completedUpload.path(),
                completedUpload.sourceFileType()
        );
        String csv = ExcelUtils.excelToCsv(
                completedUpload.path(),
                completedUpload.sourceFileType()
        );

        return submitAnalysis(
                request,
                user,
                qualityReport,
                csv,
                completedUpload.originalFileName(),
                completedUpload.sourceFileType(),
                completedUpload.totalSize(),
                completedUpload.wholeFileSha256()
        );
    }

    @Override
    public DataQualityReport inspectData(MultipartFile file) throws Exception {
        validateDataFile(file);
        return dataQualityService.inspect(file);
    }

    @Override
    public DataQualityReport inspectData(String fileToken, User user) throws Exception {
        CompletedUploadFile completedUpload = resumableUploadService.resolveCompletedUpload(fileToken, user);
        validateCompletedUploadForAnalysis(completedUpload);
        return dataQualityService.inspect(
                completedUpload.path(),
                completedUpload.sourceFileType()
        );
    }

    @Override
    public List<AnalysisJob> listJobs(List<Long> chartIds, User user) {
        ThrowUtils.throwIf(chartIds != null && chartIds.size() > MAX_PAGE_SIZE, ErrorCode.PARAMS_ERROR);
        return analysisJobService.listForCharts(user.getId(), chartIds);
    }

    @Override
    public List<AnalysisJobEvent> listJobEvents(long jobId, User user) {
        ThrowUtils.throwIf(analysisJobService.getForUser(jobId, user.getId()) == null, ErrorCode.NOT_FOUND_ERROR);
        return analysisJobService.listEvents(jobId);
    }

    @Override
    public boolean retryJob(long jobId, User user) {
        ThrowUtils.throwIf(!analysisJobService.retry(jobId, user.getId()), ErrorCode.OPERATION_ERROR,
                "Only failed jobs can be retried.");
        AnalysisJob job = analysisJobService.getForUser(jobId, user.getId());
        updateChartStatus(job.getChartId(), AnalysisJobStatusEnum.QUEUED.getValue(), null);
        try {
            biMessageProducer.sendMessage(jobId);
        } catch (RuntimeException exception) {
            analysisJobService.fail(jobId, "Could not submit retry to the queue: " + exception.getMessage());
            updateChartStatus(job.getChartId(), AnalysisJobStatusEnum.FAILED.getValue(), "Could not submit retry to the queue.");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Unable to queue the retry");
        }
        return true;
    }

    @Override
    public boolean cancelJob(long jobId, User user) {
        AnalysisJob job = analysisJobService.getForUser(jobId, user.getId());
        ThrowUtils.throwIf(job == null, ErrorCode.NOT_FOUND_ERROR);
        ThrowUtils.throwIf(!analysisJobService.cancel(jobId, user.getId()), ErrorCode.OPERATION_ERROR,
                "Only queued or retrying jobs can be cancelled.");
        updateChartStatus(job.getChartId(), AnalysisJobStatusEnum.CANCELLED.getValue(), "Cancelled by the user.");
        return true;
    }

    /**
     * Performs the shared post-validation workflow for both multipart and resumable uploads.
     */
    private BiResponse submitAnalysis(
            GenChartByAIRequest request,
            User user,
            DataQualityReport qualityReport,
            String normalizedCsv,
            String originalFileName,
            String sourceFileType,
            long sourceFileSize,
            String sourceIdentity
    ) {
        validateGenerationRequest(request);
        ThrowUtils.throwIf(
                qualityReport.isConfirmationRequired() && !request.isQualityAcknowledged(),
                ErrorCode.PARAMS_ERROR,
                "Review the data quality report and confirm before starting analysis."
        );

        String fingerprint = fingerprint(
                user.getId(),
                request.getName(),
                request.getGoal(),
                request.getChartType(),
                sourceIdentity
        );
        Optional<AnalysisJob> activeJob = analysisJobService.findActiveJob(user.getId(), fingerprint);
        if (activeJob.isPresent()) {
            return reusedResponse(activeJob.get());
        }

        redisRateLimitManager.doRateLimit("chart:generate:" + user.getId());

        Chart chart = new Chart();
        chart.setSourceFileName(StringUtils.abbreviate(originalFileName, 512));
        chart.setSourceFileType(sourceFileType);
        chart.setSourceFileSize(sourceFileSize);
        chart.setChartType(request.getChartType());

        // The database column and AI prompt receive a deterministic bounded preview, not an unbounded file.
        chart.setChartData(limitChartData(normalizedCsv));
        chart.setStatus(AnalysisJobStatusEnum.QUEUED.getValue());
        chart.setName(request.getName());
        chart.setUserId(user.getId());
        chart.setGoal(request.getGoal());
        ThrowUtils.throwIf(!chartService.save(chart), ErrorCode.SYSTEM_ERROR, "Failed to save data");

        AnalysisJob job;
        try {
            job = analysisJobService.create(chart.getId(), user.getId(), fingerprint);
        } catch (DuplicateKeyException exception) {
            // The unique active fingerprint prevents duplicate active analyses for the same source.
            chartService.removeById(chart.getId());
            return reusedResponse(analysisJobService.findActiveJob(user.getId(), fingerprint).orElseThrow(() -> exception));
        }

        try {
            biMessageProducer.sendMessage(job.getId());
        } catch (RuntimeException exception) {
            analysisJobService.fail(job.getId(), "Could not submit the job to the queue: " + exception.getMessage());
            updateChartStatus(chart.getId(), AnalysisJobStatusEnum.FAILED.getValue(), "Could not submit the job to the queue.");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Unable to queue the analysis job");
        }

        log.info("Queued analysis job={}, chart={}", job.getId(), chart.getId());
        BiResponse response = new BiResponse();
        response.setChartId(chart.getId());
        response.setJobId(job.getId());
        return response;
    }

    private void validateGenerationRequest(GenChartByAIRequest request) {
        ThrowUtils.throwIf(
                request == null || StringUtils.isBlank(request.getGoal()),
                ErrorCode.PARAMS_ERROR,
                "Analysis goal is required"
        );
        ThrowUtils.throwIf(
                StringUtils.isBlank(request.getName()),
                ErrorCode.PARAMS_ERROR,
                "Name is required"
        );
    }

    /**
     * Keeps successful transport from becoming an unbounded spreadsheet, database, or AI workload.
     */
    private void validateCompletedUploadForAnalysis(CompletedUploadFile completedUpload) {
        ThrowUtils.throwIf(
                completedUpload.totalSize() > MAX_ANALYSIS_SOURCE_BYTES,
                ErrorCode.PARAMS_ERROR,
                "The uploaded file exceeds the 5 MB analysis limit."
        );
        ThrowUtils.throwIf(
                !List.of("csv", "xlsx").contains(completedUpload.sourceFileType()),
                ErrorCode.PARAMS_ERROR,
                "Unsupported file format."
        );
    }

    /**
     * Stores a UTF-8-safe prefix that fits MySQL TEXT and keeps AI prompts bounded.
     */
    private String limitChartData(String normalizedCsv) {
        String csv = StringUtils.defaultString(normalizedCsv);
        if (csv.getBytes(StandardCharsets.UTF_8).length <= MAX_CHART_DATA_BYTES) {
            return csv;
        }

        int byteBudget = (int) (MAX_CHART_DATA_BYTES
                - CHART_DATA_TRUNCATION_NOTICE.getBytes(StandardCharsets.UTF_8).length);
        int byteCount = 0;
        int endIndex = 0;

        while (endIndex < csv.length()) {
            int codePoint = csv.codePointAt(endIndex);
            int codePointBytes = utf8ByteLength(codePoint);
            if (byteCount + codePointBytes > byteBudget) {
                break;
            }
            byteCount += codePointBytes;
            endIndex += Character.charCount(codePoint);
        }

        return csv.substring(0, endIndex) + CHART_DATA_TRUNCATION_NOTICE;
    }

    private int utf8ByteLength(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }

    private Chart requireChart(long chartId) {
        Chart chart = chartService.getById(chartId);
        ThrowUtils.throwIf(chart == null, ErrorCode.NOT_FOUND_ERROR);
        return chart;
    }

    private QueryWrapper<Chart> queryWrapper(ChartQueryRequest request) {
        validateAdvancedSearchFilters(request);

        String keyword = StringUtils.trimToNull(request.getKeyWord());
        List<String> chartTypes = cleanValues(request.getChartTypes());
        List<String> statuses = normalizeStatuses(request.getStatuses());
        QueryWrapper<Chart> wrapper = new QueryWrapper<>();
        // One keyword searches the chart title, original upload filename,
// and normalized source-data text. XLSX is stored as normalized CSV in chartData.
        wrapper.and(
                StringUtils.isNotBlank(keyword),
                query -> query.like("name", keyword)
                        .or()
                        .like("sourceFileName", keyword)
                        .or()
                        .like("chartData", keyword)
        );

// Multi-select chart type filter.
        wrapper.in(!chartTypes.isEmpty(), "chartType", chartTypes);

// Multi-select workflow-status filter.
        wrapper.in(!statuses.isEmpty(), "status", statuses);

// Original source-file format filter.
        wrapper.eq(
                StringUtils.isNotBlank(request.getSourceFileType()),
                "sourceFileType",
                request.getSourceFileType()
        );

// Source-file size range, measured in bytes.
        wrapper.ge(
                request.getMinSourceFileSize() != null,
                "sourceFileSize",
                request.getMinSourceFileSize()
        );

        wrapper.le(
                request.getMaxSourceFileSize() != null,
                "sourceFileSize",
                request.getMaxSourceFileSize()
        );

// Only build the lower date condition when the user selected a start date.
        if (request.getGeneratedFrom() != null) {
            wrapper.ge(
                    "generatedAt",
                    request.getGeneratedFrom().atStartOfDay()
            );
        }

// Use the next day's midnight as an exclusive upper bound.
// This makes the selected end date inclusive.
        if (request.getGeneratedTo() != null) {
            wrapper.lt(
                    "generatedAt",
                    request.getGeneratedTo().plusDays(1).atStartOfDay()
            );
        }
        wrapper.eq(request.getId() != null && request.getId() > 0, "id", request.getId());
        wrapper.eq(StringUtils.isNotBlank(request.getGoal()), "goal", request.getGoal());
        wrapper.like(StringUtils.isNotBlank(request.getName()), "name", request.getName());
        wrapper.eq(StringUtils.isNotBlank(request.getChartType()), "chartType", request.getChartType());
        wrapper.eq(ObjectUtils.isNotEmpty(request.getUserId()), "userId", request.getUserId());
        wrapper.orderBy(SqlUtils.validSortField(request.getSortField()),
                CommonConstant.SORT_ORDER_ASC.equals(request.getSortOrder()), request.getSortField());
        return wrapper;
    }

    private void validateAdvancedSearchFilters(ChartQueryRequest request) {
        ThrowUtils.throwIf(
                StringUtils.length(request.getKeyWord()) > 100,
                ErrorCode.PARAMS_ERROR,
                "Search keyword must not exceed 100 characters."
        );

        ThrowUtils.throwIf(
                request.getMinSourceFileSize() != null && request.getMinSourceFileSize() < 0,
                ErrorCode.PARAMS_ERROR,
                "Minimum source file size cannot be negative."
        );

        ThrowUtils.throwIf(
                request.getMaxSourceFileSize() != null && request.getMaxSourceFileSize() < 0,
                ErrorCode.PARAMS_ERROR,
                "Maximum source file size cannot be negative."
        );

        ThrowUtils.throwIf(
                request.getMinSourceFileSize() != null
                        && request.getMaxSourceFileSize() != null
                        && request.getMinSourceFileSize() > request.getMaxSourceFileSize(),
                ErrorCode.PARAMS_ERROR,
                "Minimum source file size cannot exceed the maximum."
        );

        ThrowUtils.throwIf(
                request.getGeneratedFrom() != null
                        && request.getGeneratedTo() != null
                        && request.getGeneratedFrom().isAfter(request.getGeneratedTo()),
                ErrorCode.PARAMS_ERROR,
                "Generation start date cannot be after the end date."
        );
    }

    private List<String> cleanValues(List<String> values) {
        if (values == null) {
            return List.of();
        }

        return values.stream()
                .map(StringUtils::trimToNull)
                .filter(value -> value != null)
                .distinct()
                .toList();
    }

    private List<String> normalizeStatuses(List<String> requestedStatuses) {
        Set<String> databaseStatuses = new LinkedHashSet<>();

        for (String status : cleanValues(requestedStatuses)) {
            switch (status.toLowerCase(Locale.ROOT)) {
                case "queued" -> {
                    // Supports legacy chart records created before the job-center refactor.
                    databaseStatuses.add("queued");
                    databaseStatuses.add("wait");
                }
                case "completed", "succeeded" -> {
                    // Supports both legacy and new successful-status values.
                    databaseStatuses.add("succeeded");
                    databaseStatuses.add("succeed");
                }
                case "analyzing", "analysing" -> databaseStatuses.add("running");
                default -> databaseStatuses.add(status.toLowerCase(Locale.ROOT));
            }
        }

        return List.copyOf(databaseStatuses);
    }

    private void validateDataFile(MultipartFile file) {
        ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "Select a CSV or XLSX file.");
        ThrowUtils.throwIf(file.getSize() > MAX_MULTIPART_UPLOAD_BYTES, ErrorCode.SYSTEM_ERROR, "File is too large");
        String suffix = FileUtil.getSuffix(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        ThrowUtils.throwIf(!List.of("csv", "xlsx").contains(suffix), ErrorCode.SYSTEM_ERROR, "Unsupported file format");
    }

    private void updateChartStatus(long chartId, String status, String message) {
        Chart update = new Chart();
        update.setId(chartId);
        update.setStatus(status);
        update.setExecMessage(message);
        chartService.updateById(update);
    }

    private BiResponse reusedResponse(AnalysisJob job) {
        BiResponse response = new BiResponse();
        response.setChartId(job.getChartId());
        response.setJobId(job.getId());
        response.setReused(true);
        return response;
    }

    private String fingerprint(long userId, String name, String goal, String chartType, String csv) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest((userId + "\u0000" + name + "\u0000" + goal
                    + "\u0000" + StringUtils.defaultString(chartType) + "\u0000" + csv).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
