package com.zachary.BI.BiMq;

import com.zachary.BI.exception.NonRetryableAiException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpStatusCodeException;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * Decides whether a failed analysis attempt is worth retrying.
 * <p>
 * Retrying a request the provider rejected as invalid, unauthorised or unpaid (400, 401, 402, 403, 404, 413, 422)
 * only repeats the same answer, so those jobs fail at once with the real reason. Timeouts, connection errors,
 * 5xx, 408 and 429 are transient. Anything unrecognised, including an answer that could not be parsed, is retried:
 * the model's output varies between calls.
 */
public final class AiFailureClassifier {

    private static final Set<Integer> RETRYABLE_CLIENT_ERRORS = Set.of(408, 429);

    private AiFailureClassifier() {
    }

    public static AiFailure classify(Throwable failure) {
        if (failure instanceof NonRetryableAiException) {
            return AiFailure.permanent();
        }
        if (failure instanceof HttpStatusCodeException http) {
            int status = http.getStatusCode().value();
            if (http.getStatusCode().is5xxServerError() || RETRYABLE_CLIENT_ERRORS.contains(status)) {
                return AiFailure.retryable(retryAfter(http.getResponseHeaders()));
            }
            return AiFailure.permanent();
        }
        return AiFailure.retryable(null);
    }

    /**
     * Reads Retry-After, which is either a number of seconds or an HTTP date.
     *
     * @return null when absent or unreadable
     */
    static Duration retryAfter(HttpHeaders headers) {
        String value = headers == null ? null : StringUtils.trimToNull(headers.getFirst(HttpHeaders.RETRY_AFTER));
        if (value == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException notSeconds) {
            try {
                Duration untilDate = Duration.between(ZonedDateTime.now(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME));
                return untilDate.isNegative() ? Duration.ZERO : untilDate;
            } catch (DateTimeParseException notDate) {
                return null;
            }
        }
    }

    /**
     * @param retryAfter how long the provider asked us to wait, or null
     */
    public record AiFailure(boolean retryable, Duration retryAfter) {

        static AiFailure permanent() {
            return new AiFailure(false, null);
        }

        static AiFailure retryable(Duration retryAfter) {
            return new AiFailure(true, retryAfter);
        }
    }
}
