package com.zachary.BI.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "bi.analysis.recovery")
@Data
public class AnalysisRecoveryProperties {

    /**
     * A running job older than this is treated as abandoned by a crashed worker.
     * Must be comfortably longer than the slowest legitimate AI call.
     */
    private Duration staleRunningAfter = Duration.ofMinutes(10);

    /**
     * A queued or retrying job unchanged for this long is assumed to have lost its RabbitMQ message.
     * Must be longer than the longest retry delay plus normal queue backlog.
     */
    private Duration stalePendingAfter = Duration.ofMinutes(10);

    /**
     * Maximum jobs handled per category in one run, so a large backlog cannot monopolise the scheduler thread.
     */
    private int batchSize = 100;
}
