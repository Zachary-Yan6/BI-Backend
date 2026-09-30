package com.zachary.BI.service;

public interface AnalysisCompletionService {

    /**
     * Persist all successful analysis results atomically.
     */
    void persistSuccess(long jobId, long chartId, String genChart, String genResult);
}