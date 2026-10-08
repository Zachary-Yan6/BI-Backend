package com.zachary.BI.BiMq;

import com.zachary.BI.config.AnalysisRetryProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryDelayPolicyTest {

    private static final Duration S30 = Duration.ofSeconds(30);
    private static final Duration M2 = Duration.ofMinutes(2);
    private static final Duration M5 = Duration.ofMinutes(5);

    @Test
    void eachAttempt_shouldUseItsOwnTierWithoutJitterAtTheLowEnd() {
        RetryDelayPolicy policy = policy(0.0);

        assertEquals(new RetryDelayPolicy.RetryPlan(1, S30), policy.plan(1, null));
        assertEquals(new RetryDelayPolicy.RetryPlan(2, M2), policy.plan(2, null));
        assertEquals(new RetryDelayPolicy.RetryPlan(3, M5), policy.plan(3, null));
        assertEquals(3, policy.maxRetries());
    }

    @Test
    void jitter_shouldStretchTheDelayButKeepItInItsTier() {
        // random 0.5 with jitter 0.2 -> +10%
        assertEquals(new RetryDelayPolicy.RetryPlan(1, Duration.ofSeconds(33)), policy(0.5).plan(1, null));
        // Just below the top of the range stays in tier 2.
        assertEquals(2, policy(0.999).plan(2, null).tier());
    }

    @Test
    void attemptsBeyondTheConfiguredDelays_shouldReuseTheLastTier() {
        // An older job may still carry a larger maxRetries than the current configuration.
        assertEquals(new RetryDelayPolicy.RetryPlan(3, M5), policy(0.0).plan(7, null));
    }

    @Test
    void retryAfter_shouldBeHonouredAndRoutedToATierLongEnoughToHoldIt() {
        RetryDelayPolicy policy = policy(0.0);

        // 90s is longer than tier 1 can hold (36s), so it waits in tier 2 and is never delivered early.
        assertEquals(new RetryDelayPolicy.RetryPlan(2, Duration.ofSeconds(90)), policy.plan(1, Duration.ofSeconds(90)));
        // A shorter Retry-After never shortens the backoff.
        assertEquals(new RetryDelayPolicy.RetryPlan(2, M2), policy.plan(2, Duration.ofSeconds(5)));
    }

    @Test
    void retryAfter_shouldBeCappedAtTheLongestDelay() {
        RetryDelayPolicy policy = policy(0.0);

        assertEquals(Duration.ofMinutes(6), policy.maxDelay());
        assertEquals(new RetryDelayPolicy.RetryPlan(3, Duration.ofMinutes(6)), policy.plan(1, Duration.ofHours(1)));
    }

    @Test
    void invalidConfiguration_shouldFailAtStartup() {
        assertThrows(IllegalStateException.class, () -> policy(List.of(), 0.2));
        assertThrows(IllegalStateException.class, () -> policy(List.of(M2, S30), 0.2));
        assertThrows(IllegalStateException.class, () -> policy(List.of(S30, S30), 0.2));
        assertThrows(IllegalStateException.class, () -> policy(List.of(Duration.ZERO, S30), 0.2));
        assertThrows(IllegalStateException.class, () -> policy(List.of(S30), -0.1));
    }

    private static RetryDelayPolicy policy(double random) {
        AnalysisRetryProperties properties = new AnalysisRetryProperties();
        properties.setDelays(List.of(S30, M2, M5));
        properties.setJitter(0.2);
        return new RetryDelayPolicy(properties, () -> random);
    }

    private static RetryDelayPolicy policy(List<Duration> delays, double jitter) {
        AnalysisRetryProperties properties = new AnalysisRetryProperties();
        properties.setDelays(delays);
        properties.setJitter(jitter);
        return new RetryDelayPolicy(properties, () -> 0.0);
    }
}
