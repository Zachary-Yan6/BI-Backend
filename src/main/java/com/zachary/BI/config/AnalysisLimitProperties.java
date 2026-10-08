package com.zachary.BI.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Caps on analysis jobs that are queued, running or retrying. Each one is an AI call that will be paid for and a
 * slot in the single FIFO queue that every user shares, so without caps one user could submit thousands of slightly
 * different analyses and hold every other user's jobs back for hours.
 */
@Component
@ConfigurationProperties(prefix = "bi.analysis.limits")
@Data
public class AnalysisLimitProperties {

    /**
     * Active jobs one user may have. Enforced exactly: a user's submissions are serialised on their user row.
     */
    private int maxActiveJobsPerUser = 5;

    /**
     * Active jobs across all users, which bounds the backlog. A soft limit: submissions by different users at the
     * same moment may pass it by a few.
     */
    private int maxActiveJobs = 200;
}
