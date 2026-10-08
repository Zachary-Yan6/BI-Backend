package com.zachary.BI.scheduler;

import com.zachary.BI.BiMq.BiMessageProducer;
import com.zachary.BI.BiMq.RetryDelayPolicy;
import com.zachary.BI.config.AnalysisRecoveryProperties;
import com.zachary.BI.model.entity.AnalysisJob;
import com.zachary.BI.model.entity.Chart;
import com.zachary.BI.model.enums.AnalysisJobStatusEnum;
import com.zachary.BI.service.AnalysisJobService;
import com.zachary.BI.service.ChartService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;

/**
 * Safety net for analysis jobs that the normal message flow can no longer move forward:
 * <ul>
 *     <li>running jobs whose worker died mid-analysis (redelivery is ignored because the job is already running);</li>
 *     <li>queued or retrying jobs whose RabbitMQ message was never published or was lost.</li>
 * </ul>
 * Every state change is a conditional update, so running several instances of this task is safe.
 */
@Component
@ConditionalOnProperty(prefix = "bi.analysis.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class AnalysisJobRecoveryTask {

    @Resource
    private AnalysisJobService analysisJobService;
    @Resource
    private ChartService chartService;
    @Resource
    private BiMessageProducer biMessageProducer;
    @Resource
    private AnalysisRecoveryProperties properties;
    @Resource
    private RetryDelayPolicy retryDelayPolicy;
    @Value("${bi.ai.read-timeout:PT3M}")
    private Duration aiReadTimeout;

    /**
     * A worker still waiting for the AI provider, or a job still waiting out its retry delay, must never look
     * abandoned; otherwise it would be reclaimed and paid for twice. Fail at startup rather than in production traffic.
     */
    @PostConstruct
    void validateTimeouts() {
        if (aiReadTimeout.compareTo(properties.getStaleRunningAfter()) >= 0) {
            throw new IllegalStateException("bi.ai.read-timeout (" + aiReadTimeout
                    + ") must be shorter than bi.analysis.recovery.stale-running-after ("
                    + properties.getStaleRunningAfter() + ")");
        }
        // A job waiting in a retry queue is "retrying" and untouched; if the wait outlasted the stale threshold,
        // this task would republish it early and the job would be scheduled twice.
        if (retryDelayPolicy.maxDelay().compareTo(properties.getStalePendingAfter()) >= 0) {
            throw new IllegalStateException("The longest retry delay (" + retryDelayPolicy.maxDelay()
                    + ", from bi.analysis.retry.delays and jitter) must be shorter than "
                    + "bi.analysis.recovery.stale-pending-after (" + properties.getStalePendingAfter() + ")");
        }
    }

    @Scheduled(initialDelayString = "${bi.analysis.recovery.interval:PT1M}",
            fixedDelayString = "${bi.analysis.recovery.interval:PT1M}")
    public void recover() {
        recoverStaleRunningJobs();
        republishStalePendingJobs();
    }

    void recoverStaleRunningJobs() {
        Date startedBefore = new Date(System.currentTimeMillis() - properties.getStaleRunningAfter().toMillis());
        for (AnalysisJob job : analysisJobService.listStaleRunning(startedBefore, properties.getBatchSize())) {
            try {
                AnalysisJobStatusEnum outcome = analysisJobService.recoverStaleRunning(job.getId(), startedBefore);
                if (outcome == AnalysisJobStatusEnum.RETRYING) {
                    updateChartStatus(job.getChartId(), outcome, "The worker stopped responding; retrying.");
                    // If this publish fails, the job is now retrying and the pending check below picks it up later.
                    biMessageProducer.sendMessage(job.getId());
                } else if (outcome == AnalysisJobStatusEnum.FAILED) {
                    updateChartStatus(job.getChartId(), outcome, "The worker stopped responding and no retries remain.");
                    biMessageProducer.sendToDeadLetterQueue(job.getId());
                }
                if (outcome != null) {
                    log.warn("Recovered stale running analysis job {} as {}", job.getId(), outcome.getValue());
                }
            } catch (RuntimeException exception) {
                // One bad job must not stop the rest of the batch; it will be seen again next run.
                log.error("Could not recover stale running analysis job {}", job.getId(), exception);
            }
        }
    }

    void republishStalePendingJobs() {
        long idleSeconds = properties.getStalePendingAfter().toSeconds();
        for (AnalysisJob job : analysisJobService.listStalePending(idleSeconds, properties.getBatchSize())) {
            try {
                if (analysisJobService.claimStalePending(job.getId(), idleSeconds)) {
                    // A duplicate message is harmless: the consumer's start() lets only one delivery run the job.
                    biMessageProducer.sendMessage(job.getId());
                    log.warn("Republished stale {} analysis job {}", job.getStatus(), job.getId());
                }
            } catch (RuntimeException exception) {
                log.error("Could not republish stale analysis job {}", job.getId(), exception);
            }
        }
    }

    private void updateChartStatus(long chartId, AnalysisJobStatusEnum status, String message) {
        Chart update = new Chart();
        update.setId(chartId);
        update.setStatus(status.getValue());
        update.setExecMessage(message);
        chartService.updateById(update);
    }
}
