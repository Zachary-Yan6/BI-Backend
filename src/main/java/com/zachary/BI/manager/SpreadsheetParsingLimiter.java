package com.zachary.BI.manager;

import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bounds how many spreadsheets this instance parses at once.
 * <p>
 * A parsed file holds up to about 50 MB of heap (see ExcelUtils limits), and parsing runs on request threads, so
 * the risk is concurrency, not request rate: six simultaneous 5 MB files used to exhaust the 768 MB production heap.
 * A per-second rate limit does not bound that, and one uploaded file token can be parsed again and again.
 * Each user may parse one file at a time, so a single user cannot take every permit.
 */
@Component
public class SpreadsheetParsingLimiter {

    private final Semaphore permits;
    private final Duration acquireTimeout;
    private final Set<Long> usersParsing = ConcurrentHashMap.newKeySet();

    public SpreadsheetParsingLimiter(@Value("${bi.analysis.parsing.max-concurrent:2}") int maxConcurrent,
                                     @Value("${bi.analysis.parsing.acquire-timeout:PT10S}") Duration acquireTimeout) {
        if (maxConcurrent < 1) {
            throw new IllegalStateException("bi.analysis.parsing.max-concurrent must be at least 1");
        }
        this.permits = new Semaphore(maxConcurrent, true);
        this.acquireTimeout = acquireTimeout;
    }

    /**
     * Runs the parse while holding a permit; waits up to the acquire timeout for one.
     *
     * @throws BusinessException TOO_MANY_REQUESTS when the user is already parsing a file or no permit frees up
     */
    public <T> T parse(long userId, Callable<T> task) throws Exception {
        if (!usersParsing.add(userId)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "Another file of yours is still being processed. Please wait for it to finish.");
        }
        try {
            if (!permits.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                        "The server is busy processing other files. Please try again shortly.");
            }
            try {
                return task.call();
            } finally {
                permits.release();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Interrupted while waiting to process the file.");
        } finally {
            usersParsing.remove(userId);
        }
    }
}
