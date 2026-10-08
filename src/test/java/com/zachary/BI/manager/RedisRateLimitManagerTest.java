package com.zachary.BI.manager;

import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RedisRateLimitManagerTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisRateLimitManager limiter = new RedisRateLimitManager();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(limiter, "stringRedisTemplate", redis);
    }

    @Test
    void whenRedisAnswers_itsDecisionApplies() {
        redisReturns(1L);
        assertDoesNotThrow(() -> limiter.doRateLimit("chart:generate:1"));

        redisReturns(0L);
        assertTooManyRequests(() -> limiter.doRateLimit("chart:generate:1"));
    }

    @Test
    void whenRedisIsDown_theSameWindowIsEnforcedLocally() {
        // Previously the Redis exception reached the user as "System error", stopping every generation.
        redisFails(new RedisConnectionFailureException("Unable to connect to Redis"));

        assertDoesNotThrow(() -> limiter.doRateLimit("chart:generate:1"));
        assertDoesNotThrow(() -> limiter.doRateLimit("chart:generate:1"));
        assertTooManyRequests(() -> limiter.doRateLimit("chart:generate:1"));
        // Each key has its own window.
        assertDoesNotThrow(() -> limiter.doRateLimit("chart:generate:2"));
    }

    @Test
    void whenRedisTimesOut_theLocalWindowSlides() throws Exception {
        redisFails(new QueryTimeoutException("Redis command timed out"));
        limiter.doRateLimit("chart:generate:1");
        limiter.doRateLimit("chart:generate:1");
        assertTooManyRequests(() -> limiter.doRateLimit("chart:generate:1"));

        Thread.sleep(1_100);

        assertDoesNotThrow(() -> limiter.doRateLimit("chart:generate:1"));
    }

    @Test
    void afterAFailure_redisShouldBeSkippedForAWhile() {
        // Otherwise every request during an outage would first wait for the Redis timeout.
        redisFails(new RedisConnectionFailureException("down"));

        limiter.doRateLimit("chart:generate:1");
        limiter.doRateLimit("chart:generate:2");
        limiter.doRateLimit("chart:generate:3");

        verify(redis, times(1)).execute(any(), anyList(), any(), any(), any());
    }

    @Test
    void whenRedisRecovers_theLocalWindowsAreDropped() {
        ReflectionTestUtils.setField(limiter, "redisRetryIntervalMillis", 0L);
        redisFails(new RedisConnectionFailureException("down"));
        limiter.doRateLimit("chart:generate:1");
        limiter.doRateLimit("chart:generate:1");

        redisReturns(1L);
        limiter.doRateLimit("chart:generate:1");

        // A later outage starts from an empty local window, not from requests counted before the recovery.
        redisFails(new RedisConnectionFailureException("down again"));
        assertDoesNotThrow(() -> limiter.doRateLimit("chart:generate:1"));
        assertDoesNotThrow(() -> limiter.doRateLimit("chart:generate:1"));
    }

    // doReturn/doThrow: re-stubbing with when(...) would invoke the previous, throwing stub.
    private void redisReturns(Long result) {
        doReturn(result).when(redis).execute(any(), anyList(), any(), any(), any());
    }

    private void redisFails(RuntimeException failure) {
        doThrow(failure).when(redis).execute(any(), anyList(), any(), any(), any());
    }

    private static void assertTooManyRequests(Executable call) {
        BusinessException exception = assertThrows(BusinessException.class, call);
        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), exception.getCode());
    }
}
