package com.zachary.BI.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.exception.ThrowUtils;
import com.zachary.BI.model.entity.AnalysisJob;
import com.zachary.BI.model.entity.AnalysisJobEvent;
import com.zachary.BI.model.entity.Chart;
import com.zachary.BI.model.enums.AnalysisJobStatusEnum;
import com.zachary.BI.mapper.AnalysisJobEventMapper;
import com.zachary.BI.mapper.AnalysisJobMapper;
import com.zachary.BI.service.AnalysisJobService;
import com.zachary.BI.service.ChartService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class AnalysisJobServiceImpl implements AnalysisJobService {
    private static final int DEFAULT_MAX_RETRIES = 3;

    @Resource
    private AnalysisJobMapper analysisJobMapper;
    @Resource
    private AnalysisJobEventMapper analysisJobEventMapper;
    @Resource
    private ChartService chartService;

    @Override
    public Optional<AnalysisJob> findActiveJob(long userId, String fingerprint) {
        return Optional.ofNullable(analysisJobMapper.selectOne(new LambdaQueryWrapper<AnalysisJob>()
                .eq(AnalysisJob::getUserId, userId)
                .eq(AnalysisJob::getActiveFingerprint, fingerprint)
                .last("LIMIT 1")));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AnalysisJob create(long chartId, long userId, String fingerprint) {
        AnalysisJob job = new AnalysisJob();
        job.setChartId(chartId);
        job.setUserId(userId);
        job.setRequestId(UUID.randomUUID().toString());
        job.setDataFingerprint(fingerprint);
        job.setActiveFingerprint(fingerprint);
        job.setStatus(AnalysisJobStatusEnum.QUEUED.getValue());
        job.setRetryCount(0);
        job.setMaxRetries(DEFAULT_MAX_RETRIES);
        analysisJobMapper.insert(job);
        addEvent(job.getId(), job.getStatus(), "Analysis request accepted and queued.");
        return job;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean start(long jobId) {
        Date now = new Date();
        int changed = analysisJobMapper.update(null, new LambdaUpdateWrapper<AnalysisJob>()
                .eq(AnalysisJob::getId, jobId)
                .in(AnalysisJob::getStatus, AnalysisJobStatusEnum.QUEUED.getValue(), AnalysisJobStatusEnum.RETRYING.getValue())
                .set(AnalysisJob::getStatus, AnalysisJobStatusEnum.RUNNING.getValue())
                .set(AnalysisJob::getStartedAt, now));
        if (changed > 0) {
            addEvent(jobId, AnalysisJobStatusEnum.RUNNING.getValue(), "Worker started processing the analysis.");
        }
        return changed > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void succeed(long jobId) {
        Date now = new Date();
        int updated = analysisJobMapper.update(null, new LambdaUpdateWrapper<AnalysisJob>()
                .eq(AnalysisJob::getId, jobId)
                .eq(AnalysisJob::getStatus, AnalysisJobStatusEnum.RUNNING.getValue())
                .set(AnalysisJob::getStatus, AnalysisJobStatusEnum.SUCCEEDED.getValue())
                .set(AnalysisJob::getActiveFingerprint, null)
                .set(AnalysisJob::getFailureReason, null)
                .set(AnalysisJob::getFinishedAt, now));

// If this throws, the outer persistSuccess transaction rolls back chart changes too.
        ThrowUtils.throwIf(updated != 1, ErrorCode.OPERATION_ERROR,
                "Failed to mark the analysis job as succeeded.");

        addEvent(jobId, AnalysisJobStatusEnum.SUCCEEDED.getValue(),
                "Analysis completed successfully.");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int scheduleRetry(long jobId, String reason) {
        AnalysisJob job = analysisJobMapper.selectById(jobId);
        if (job == null || !AnalysisJobStatusEnum.RUNNING.getValue().equals(job.getStatus())) {
            return -1;
        }
        int nextAttempt = job.getRetryCount() + 1;
        if (nextAttempt > job.getMaxRetries()) {
            return 0;
        }
        int changed = analysisJobMapper.update(null, new LambdaUpdateWrapper<AnalysisJob>()
                .eq(AnalysisJob::getId, jobId)
                .eq(AnalysisJob::getStatus, AnalysisJobStatusEnum.RUNNING.getValue())
                .set(AnalysisJob::getStatus, AnalysisJobStatusEnum.RETRYING.getValue())
                .set(AnalysisJob::getRetryCount, nextAttempt)
                .set(AnalysisJob::getFailureReason, truncate(reason)));
        if (changed == 0) {
            // The recovery task or a user action changed the job after it was read.
            return -1;
        }
        addEvent(jobId, AnalysisJobStatusEnum.RETRYING.getValue(),
                "Attempt " + nextAttempt + " failed; retry has been scheduled.");
        return nextAttempt;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void fail(long jobId, String reason) {
        Date now = new Date();
        analysisJobMapper.update(null, new LambdaUpdateWrapper<AnalysisJob>()
                .eq(AnalysisJob::getId, jobId)
                .in(AnalysisJob::getStatus, AnalysisJobStatusEnum.RUNNING.getValue(), AnalysisJobStatusEnum.RETRYING.getValue(), AnalysisJobStatusEnum.QUEUED.getValue())
                .set(AnalysisJob::getStatus, AnalysisJobStatusEnum.FAILED.getValue())
                .set(AnalysisJob::getActiveFingerprint, null)
                .set(AnalysisJob::getFailureReason, truncate(reason))
                .set(AnalysisJob::getFinishedAt, now));
        addEvent(jobId, AnalysisJobStatusEnum.FAILED.getValue(), "Analysis failed after all available attempts.");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(long jobId, long userId) {
        int changed = analysisJobMapper.update(null, new LambdaUpdateWrapper<AnalysisJob>()
                .eq(AnalysisJob::getId, jobId)
                .eq(AnalysisJob::getUserId, userId)
                .in(AnalysisJob::getStatus, AnalysisJobStatusEnum.QUEUED.getValue(), AnalysisJobStatusEnum.RETRYING.getValue())
                .set(AnalysisJob::getStatus, AnalysisJobStatusEnum.CANCELLED.getValue())
                .set(AnalysisJob::getActiveFingerprint, null)
                .set(AnalysisJob::getCancelledAt, new Date()));
        if (changed > 0) {
            addEvent(jobId, AnalysisJobStatusEnum.CANCELLED.getValue(), "Cancelled by the user before processing started.");
        }
        return changed > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cancelActiveJobsForChart(long chartId) {
        List<String> activeStatuses = List.of(AnalysisJobStatusEnum.QUEUED.getValue(),
                AnalysisJobStatusEnum.RUNNING.getValue(), AnalysisJobStatusEnum.RETRYING.getValue());
        List<AnalysisJob> activeJobs = analysisJobMapper.selectList(new LambdaQueryWrapper<AnalysisJob>()
                .eq(AnalysisJob::getChartId, chartId)
                .in(AnalysisJob::getStatus, activeStatuses));

        int cancelled = 0;
        for (AnalysisJob job : activeJobs) {
            // Unlike a user cancel, running jobs are included: the in-flight AI call cannot be stopped, but once the
            // job is no longer running the worker's result is discarded and no further retries are scheduled.
            int changed = analysisJobMapper.update(null, new LambdaUpdateWrapper<AnalysisJob>()
                    .eq(AnalysisJob::getId, job.getId())
                    .in(AnalysisJob::getStatus, activeStatuses)
                    .set(AnalysisJob::getStatus, AnalysisJobStatusEnum.CANCELLED.getValue())
                    .set(AnalysisJob::getActiveFingerprint, null)
                    .set(AnalysisJob::getCancelledAt, new Date()));
            if (changed > 0) {
                addEvent(job.getId(), AnalysisJobStatusEnum.CANCELLED.getValue(), "Cancelled because the chart was deleted.");
                cancelled++;
            }
        }
        return cancelled;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean retry(long jobId, long userId) {
        AnalysisJob job = getForUser(jobId, userId);
        if (job == null || !AnalysisJobStatusEnum.FAILED.getValue().equals(job.getStatus())) {
            return false;
        }
        Optional<AnalysisJob> activeJob = findActiveJob(userId, job.getDataFingerprint());
        if (activeJob.isPresent() && !activeJob.get().getId().equals(jobId)) {
            return false;
        }
        int changed = analysisJobMapper.update(null, new LambdaUpdateWrapper<AnalysisJob>()
                .eq(AnalysisJob::getId, jobId)
                .eq(AnalysisJob::getStatus, AnalysisJobStatusEnum.FAILED.getValue())
                .set(AnalysisJob::getStatus, AnalysisJobStatusEnum.QUEUED.getValue())
                .set(AnalysisJob::getRetryCount, 0)
                .set(AnalysisJob::getActiveFingerprint, job.getDataFingerprint())
                .set(AnalysisJob::getFailureReason, null)
                .set(AnalysisJob::getFinishedAt, null));
        if (changed > 0) {
            addEvent(jobId, AnalysisJobStatusEnum.QUEUED.getValue(), "User requested another processing run.");
        }
        return changed > 0;
    }

    @Override
    public AnalysisJob getForUser(long jobId, long userId) {
        return analysisJobMapper.selectOne(new LambdaQueryWrapper<AnalysisJob>()
                .eq(AnalysisJob::getId, jobId).eq(AnalysisJob::getUserId, userId));
    }

    @Override
    public AnalysisJob getById(long jobId) {
        return analysisJobMapper.selectById(jobId);
    }

    @Override
    public List<AnalysisJob> listForCharts(long userId, List<Long> chartIds) {
        if (chartIds == null || chartIds.isEmpty()) {
            return List.of();
        }
        return analysisJobMapper.selectList(new LambdaQueryWrapper<AnalysisJob>()
                .eq(AnalysisJob::getUserId, userId)
                .in(AnalysisJob::getChartId, chartIds)
                .orderByDesc(AnalysisJob::getCreateTime)
                .orderByDesc(AnalysisJob::getId));
    }

    @Override
    public List<AnalysisJobEvent> listEvents(long jobId) {
        // createTime has one-second resolution and a job often records several events within one second.
        // Snowflake ids grow with time (milliseconds, then a sequence), so they give the exact recording order;
        // without them the timeline relied on whatever order the query plan happened to produce.
        return analysisJobEventMapper.selectList(new LambdaQueryWrapper<AnalysisJobEvent>()
                .eq(AnalysisJobEvent::getJobId, jobId)
                .orderByAsc(AnalysisJobEvent::getCreateTime)
                .orderByAsc(AnalysisJobEvent::getId));
    }

    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class
    )
    public void requeueAfterPersistenceFailure(long jobId, String reason) {
        AnalysisJob job = analysisJobMapper.selectById(jobId);

        ThrowUtils.throwIf(
                job == null || !AnalysisJobStatusEnum.RUNNING.getValue().equals(job.getStatus()),
                ErrorCode.OPERATION_ERROR,
                "Only a running job can be returned to the queue."
        );

        // Move the job back to queued only if no worker has already changed it.
        int jobUpdated = analysisJobMapper.update(
                null,
                new LambdaUpdateWrapper<AnalysisJob>()
                        .eq(AnalysisJob::getId, jobId)
                        .eq(AnalysisJob::getStatus, AnalysisJobStatusEnum.RUNNING.getValue())
                        .set(AnalysisJob::getStatus, AnalysisJobStatusEnum.QUEUED.getValue())
                        .set(AnalysisJob::getFailureReason, truncate(reason))
        );

        ThrowUtils.throwIf(
                jobUpdated != 1,
                ErrorCode.OPERATION_ERROR,
                "Failed to return the analysis job to the queue."
        );

        // Keep chart.status and analysis_job.status consistent.
        Chart chartUpdate = new Chart();
        chartUpdate.setId(job.getChartId());
        chartUpdate.setStatus(AnalysisJobStatusEnum.QUEUED.getValue());
        chartUpdate.setExecMessage("Result persistence was interrupted. The job will be retried.");

        boolean chartUpdated = chartService.updateById(chartUpdate);

        ThrowUtils.throwIf(
                !chartUpdated,
                ErrorCode.OPERATION_ERROR,
                "Failed to return the chart to queued status."
        );

        // This event is inserted only after both status updates have succeeded.
        addEvent(
                jobId,
                AnalysisJobStatusEnum.QUEUED.getValue(),
                "Result persistence failed; the RabbitMQ message was returned to the queue."
        );
    }

    @Override
    public List<AnalysisJob> listStaleRunning(Date startedBefore, int limit) {
        return analysisJobMapper.selectList(new LambdaQueryWrapper<AnalysisJob>()
                .eq(AnalysisJob::getStatus, AnalysisJobStatusEnum.RUNNING.getValue())
                .lt(AnalysisJob::getStartedAt, startedBefore)
                .orderByAsc(AnalysisJob::getStartedAt)
                .last("LIMIT " + limit));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AnalysisJobStatusEnum recoverStaleRunning(long jobId, Date startedBefore) {
        String reason = "The worker did not finish within the processing timeout.";

        // Both updates repeat the stale condition, so a worker that finishes in the meantime always wins.
        int retried = analysisJobMapper.update(null, new LambdaUpdateWrapper<AnalysisJob>()
                .eq(AnalysisJob::getId, jobId)
                .eq(AnalysisJob::getStatus, AnalysisJobStatusEnum.RUNNING.getValue())
                .lt(AnalysisJob::getStartedAt, startedBefore)
                .apply("retryCount < maxRetries")
                .set(AnalysisJob::getStatus, AnalysisJobStatusEnum.RETRYING.getValue())
                .setSql("retryCount = retryCount + 1")
                .set(AnalysisJob::getFailureReason, reason));
        if (retried == 1) {
            addEvent(jobId, AnalysisJobStatusEnum.RETRYING.getValue(),
                    "The worker stopped responding; the job has been queued again.");
            return AnalysisJobStatusEnum.RETRYING;
        }

        int failed = analysisJobMapper.update(null, new LambdaUpdateWrapper<AnalysisJob>()
                .eq(AnalysisJob::getId, jobId)
                .eq(AnalysisJob::getStatus, AnalysisJobStatusEnum.RUNNING.getValue())
                .lt(AnalysisJob::getStartedAt, startedBefore)
                .set(AnalysisJob::getStatus, AnalysisJobStatusEnum.FAILED.getValue())
                .set(AnalysisJob::getActiveFingerprint, null)
                .set(AnalysisJob::getFailureReason, reason)
                .set(AnalysisJob::getFinishedAt, new Date()));
        if (failed == 1) {
            addEvent(jobId, AnalysisJobStatusEnum.FAILED.getValue(),
                    "The worker stopped responding and no retries remain.");
            return AnalysisJobStatusEnum.FAILED;
        }
        return null;
    }

    @Override
    public List<AnalysisJob> listStalePending(long idleSeconds, int limit) {
        // updateTime is written by MySQL, so compare it with MySQL's clock rather than the JVM's.
        return analysisJobMapper.selectList(new LambdaQueryWrapper<AnalysisJob>()
                .in(AnalysisJob::getStatus, AnalysisJobStatusEnum.QUEUED.getValue(),
                        AnalysisJobStatusEnum.RETRYING.getValue())
                .apply("updateTime < NOW() - INTERVAL {0} SECOND", idleSeconds)
                .orderByAsc(AnalysisJob::getUpdateTime)
                .last("LIMIT " + limit));
    }

    @Override
    public boolean claimStalePending(long jobId, long idleSeconds) {
        // Touching updateTime makes other instances skip this job until it goes stale again.
        return analysisJobMapper.update(null, new LambdaUpdateWrapper<AnalysisJob>()
                .eq(AnalysisJob::getId, jobId)
                .in(AnalysisJob::getStatus, AnalysisJobStatusEnum.QUEUED.getValue(),
                        AnalysisJobStatusEnum.RETRYING.getValue())
                .apply("updateTime < NOW() - INTERVAL {0} SECOND", idleSeconds)
                .setSql("updateTime = NOW()")) == 1;
    }

    private void addEvent(long jobId, String status, String message) {
        AnalysisJobEvent event = new AnalysisJobEvent();
        event.setJobId(jobId);
        event.setStatus(status);
        event.setMessage(message);
        analysisJobEventMapper.insert(event);
    }

    private String truncate(String value) {
        return StringUtils.abbreviate(StringUtils.defaultIfBlank(value, "Unknown processing failure"), 1000);
    }
}
