package com.zachary.BI.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "bi.analysis.retry")
@Data
public class AnalysisRetryProperties {

    /**
     * Delay before each retry of a failed analysis; the number of entries is the number of retries.
     * Each entry gets its own RabbitMQ retry queue (see BiMqConfig), so the list must be strictly increasing.
     * Long enough in total to ride out a short provider outage instead of failing every job submitted during it.
     */
    private List<Duration> delays = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(5));

    /**
     * Each delay is stretched by a random fraction in [0, jitter), so jobs that failed together do not all hit the
     * provider again at the same moment.
     */
    private double jitter = 0.2;
}
