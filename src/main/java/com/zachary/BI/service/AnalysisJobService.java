package com.zachary.BI.service;

import com.zachary.BI.model.entity.AnalysisJob;
import com.zachary.BI.model.entity.AnalysisJobEvent;
import com.zachary.BI.model.enums.AnalysisJobStatusEnum;

import java.util.Date;
import java.util.List;
import java.util.Optional;

public interface AnalysisJobService {
    Optional<AnalysisJob> findActiveJob(long userId, String fingerprint);

    AnalysisJob create(long chartId, long userId, String fingerprint);

    boolean start(long jobId);

    void succeed(long jobId);

    /**
     * Moves a running job to retrying.
     *
     * @return the new attempt number, 0 when no retries remain, or -1 when the job is no longer running
     * (for example because the recovery task already reclaimed it)
     */
    int scheduleRetry(long jobId, String reason);

    void fail(long jobId, String reason);

    boolean cancel(long jobId, long userId);

    boolean retry(long jobId, long userId);

    AnalysisJob getForUser(long jobId, long userId);

    AnalysisJob getById(long jobId);

    List<AnalysisJob> listForCharts(long userId, List<Long> chartIds);

    List<AnalysisJobEvent> listEvents(long jobId);

    /**
     * Returns a running job to the queue after its completed AI result could not
     * be persisted. This does not consume an AI retry attempt.
     */
    void requeueAfterPersistenceFailure(long jobId, String reason);

    /** Running jobs whose worker started before the cutoff, oldest first. */
    List<AnalysisJob> listStaleRunning(Date startedBefore, int limit);

    /**
     * Reclaims a running job whose worker appears to have died.
     *
     * @return RETRYING or FAILED, or null when the job finished or changed in the meantime
     */
    AnalysisJobStatusEnum recoverStaleRunning(long jobId, Date startedBefore);

    /** Queued or retrying jobs that have not changed for at least {@code idleSeconds}, oldest first. */
    List<AnalysisJob> listStalePending(long idleSeconds, int limit);

    /** Atomically claims a stale pending job so only one instance republishes its message. */
    boolean claimStalePending(long jobId, long idleSeconds);
}
