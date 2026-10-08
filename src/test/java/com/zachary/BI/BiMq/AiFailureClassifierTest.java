package com.zachary.BI.BiMq;

import com.zachary.BI.exception.NonRetryableAiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiFailureClassifierTest {

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 402, 403, 404, 413, 422})
    void requestTheProviderRejected_shouldNotBeRetried(int status) {
        assertFalse(AiFailureClassifier.classify(clientError(status, new HttpHeaders())).retryable());
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void serverErrors_shouldBeRetried(int status) {
        HttpServerErrorException error = HttpServerErrorException.create(HttpStatus.valueOf(status), "error",
                new HttpHeaders(), new byte[0], null);

        assertTrue(AiFailureClassifier.classify(error).retryable());
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429})
    void transientClientErrors_shouldBeRetried(int status) {
        assertTrue(AiFailureClassifier.classify(clientError(status, new HttpHeaders())).retryable());
    }

    @Test
    void timeoutsAndUnparseableAnswers_shouldBeRetried() {
        assertTrue(AiFailureClassifier.classify(
                new ResourceAccessException("I/O error", new HttpTimeoutException("request timed out"))).retryable());
        assertTrue(AiFailureClassifier.classify(new IllegalStateException("Unable to parse AI response")).retryable());
    }

    @Test
    void missingConfiguration_shouldNotBeRetried() {
        assertFalse(AiFailureClassifier.classify(new NonRetryableAiException("DEEPSEEK_API_KEY is not configured"))
                .retryable());
    }

    @Test
    void rateLimit_shouldCarryRetryAfterInSeconds() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "45");

        AiFailureClassifier.AiFailure failure = AiFailureClassifier.classify(clientError(429, headers));

        assertTrue(failure.retryable());
        assertEquals(Duration.ofSeconds(45), failure.retryAfter());
    }

    @Test
    void retryAfter_shouldAcceptHttpDateAndIgnoreGarbage() {
        HttpHeaders date = new HttpHeaders();
        date.set(HttpHeaders.RETRY_AFTER,
                DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now().plusSeconds(120)));
        Duration untilDate = AiFailureClassifier.retryAfter(date);
        assertTrue(untilDate.compareTo(Duration.ofSeconds(110)) > 0 && untilDate.compareTo(Duration.ofSeconds(121)) < 0,
                untilDate::toString);

        HttpHeaders past = new HttpHeaders();
        past.set(HttpHeaders.RETRY_AFTER, DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now().minusHours(1)));
        assertEquals(Duration.ZERO, AiFailureClassifier.retryAfter(past));

        HttpHeaders garbage = new HttpHeaders();
        garbage.set(HttpHeaders.RETRY_AFTER, "soon");
        assertNull(AiFailureClassifier.retryAfter(garbage));
        HttpHeaders negative = new HttpHeaders();
        negative.set(HttpHeaders.RETRY_AFTER, "-5");
        assertNull(AiFailureClassifier.retryAfter(negative));
        assertNull(AiFailureClassifier.retryAfter(new HttpHeaders()));
        assertNull(AiFailureClassifier.retryAfter(null));
    }

    private static HttpClientErrorException clientError(int status, HttpHeaders headers) {
        return HttpClientErrorException.create(HttpStatus.valueOf(status), "error", headers, new byte[0], null);
    }
}
