package com.zachary.BI.manager;

import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpreadsheetParsingLimiterTest {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CountDownLatch parsing = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        executor.shutdownNow();
    }

    @Test
    void sameUser_shouldParseOneFileAtATime() throws Exception {
        SpreadsheetParsingLimiter limiter = new SpreadsheetParsingLimiter(2, Duration.ofSeconds(5));
        Future<String> first = startBlockedParse(limiter, 1L);

        // Rejected at once, without waiting for a permit: the same file token can otherwise be parsed in parallel.
        assertTooManyRequests(() -> limiter.parse(1L, () -> "second"));
        assertEquals("other user", limiter.parse(2L, () -> "other user"));

        release.countDown();
        assertEquals("first", first.get(5, TimeUnit.SECONDS));
        assertEquals("again", limiter.parse(1L, () -> "again"));
    }

    @Test
    void whenEveryPermitIsTaken_shouldWaitThenReportBusy() throws Exception {
        SpreadsheetParsingLimiter limiter = new SpreadsheetParsingLimiter(1, Duration.ofMillis(200));
        startBlockedParse(limiter, 1L);

        long start = System.nanoTime();
        assertTooManyRequests(() -> limiter.parse(2L, () -> "never runs"));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofMillis(150)) >= 0,
                "should wait for the acquire timeout before giving up");
    }

    @Test
    void failedParse_shouldReleaseThePermitAndTheUser() throws Exception {
        SpreadsheetParsingLimiter limiter = new SpreadsheetParsingLimiter(1, Duration.ofMillis(200));

        assertThrows(IllegalStateException.class, () -> limiter.parse(1L, () -> {
            throw new IllegalStateException("corrupt file");
        }));

        assertEquals("ok", limiter.parse(1L, () -> "ok"));
    }

    @Test
    void invalidConfiguration_shouldFailAtStartup() {
        assertThrows(IllegalStateException.class, () -> new SpreadsheetParsingLimiter(0, Duration.ofSeconds(1)));
    }

    private Future<String> startBlockedParse(SpreadsheetParsingLimiter limiter, long userId) throws Exception {
        Future<String> future = executor.submit(() -> limiter.parse(userId, () -> {
            parsing.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "first";
        }));
        assertTrue(parsing.await(5, TimeUnit.SECONDS), "the first parse should start");
        return future;
    }

    private static void assertTooManyRequests(Executable call) {
        BusinessException exception = assertThrows(BusinessException.class, call);
        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), exception.getCode());
    }
}
