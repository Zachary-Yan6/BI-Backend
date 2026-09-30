package com.zachary.BI.service;

import com.zachary.BI.model.entity.AnalysisJob;
import com.zachary.BI.model.entity.AnalysisJobEvent;

import java.util.List;
import java.util.Optional;

public interface AnalysisJobService {
    Optional<AnalysisJob> findActiveJob(long userId, String fingerprint);

    AnalysisJob create(long chartId, long userId, String fingerprint);

    boolean start(long jobId);

    void succeed(long jobId);

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
}
