package com.zachary.BI.BiMq;

import com.zachary.BI.config.AnalysisRetryProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Turns a retry attempt into a delay and the retry queue ("tier") that delivers it.
 * <p>
 * RabbitMQ only expires the message at the head of a queue, so a short delay queued behind a long one waits for
 * the long one. Each tier therefore holds only delays of about the same length: its configured delay plus jitter.
 */
@Component
public class RetryDelayPolicy {

    private final List<Duration> delays;
    private final double jitter;
    private final DoubleSupplier random;

    @Autowired
    public RetryDelayPolicy(AnalysisRetryProperties properties) {
        this(properties, () -> ThreadLocalRandom.current().nextDouble());
    }

    RetryDelayPolicy(AnalysisRetryProperties properties, DoubleSupplier random) {
        this.delays = List.copyOf(properties.getDelays());
        this.jitter = properties.getJitter();
        this.random = random;
        if (delays.isEmpty()) {
            throw new IllegalStateException("bi.analysis.retry.delays must contain at least one delay");
        }
        if (jitter < 0) {
            throw new IllegalStateException("bi.analysis.retry.jitter must not be negative");
        }
        for (int i = 0; i < delays.size(); i++) {
            if (!delays.get(i).isPositive() || (i > 0 && delays.get(i).compareTo(delays.get(i - 1)) <= 0)) {
                throw new IllegalStateException("bi.analysis.retry.delays must be positive and strictly increasing: "
                        + delays);
            }
        }
    }

    /**
     * One retry per configured delay.
     */
    public int maxRetries() {
        return delays.size();
    }

    /**
     * The longest delay any retry can get, Retry-After included.
     */
    public Duration maxDelay() {
        return longestInTier(delays.size() - 1);
    }

    /**
     * @param attempt    the retry number, starting at 1; attempts beyond the configured delays reuse the last one
     * @param retryAfter how long the provider asked us to wait, or null; capped at {@link #maxDelay()}
     */
    public RetryPlan plan(int attempt, Duration retryAfter) {
        int index = Math.min(Math.max(attempt, 1), delays.size()) - 1;
        Duration delay = scale(delays.get(index), 1 + jitter * random.getAsDouble());
        if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
            delay = retryAfter;
        }
        if (delay.compareTo(maxDelay()) > 0) {
            delay = maxDelay();
        }
        return new RetryPlan(tierFor(delay), delay);
    }

    /**
     * The first tier whose delays are at least this long. A Retry-After between two tiers can therefore wait up to
     * the longer tier's delay, but never less than the provider asked for.
     */
    private int tierFor(Duration delay) {
        for (int i = 0; i < delays.size(); i++) {
            if (delay.compareTo(longestInTier(i)) <= 0) {
                return i + 1;
            }
        }
        return delays.size();
    }

    private Duration longestInTier(int index) {
        return scale(delays.get(index), 1 + jitter);
    }

    private static Duration scale(Duration duration, double factor) {
        return Duration.ofMillis(Math.round(duration.toMillis() * factor));
    }

    /**
     * @param tier  the retry queue to publish to, starting at 1
     * @param delay the message TTL in that queue
     */
    public record RetryPlan(int tier, Duration delay) {
    }
}
