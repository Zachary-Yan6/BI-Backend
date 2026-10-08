package com.zachary.BI.manager;

import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-key sliding-window rate limit, shared by all instances through Redis.
 * <p>
 * Redis is not needed for anything else (sessions live in the servlet container), so its outage must not take
 * analysis down with it: when Redis cannot be reached the same window is enforced in this JVM instead. That is
 * exact for a single instance and per instance otherwise; the parsing limiter and the active-job caps, which do
 * not depend on Redis, still bound the cost.
 */
@Slf4j
@Service
public class RedisRateLimitManager {

    private static final int MAX_REQUESTS = 2;
    private static final long WINDOW_MILLIS = 1_000L;
    /** One warning per minute is enough to notice an outage without one log line per request. */
    private static final long FALLBACK_WARNING_INTERVAL_MILLIS = 60_000L;
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
            if redis.call('ZCARD', KEYS[1]) >= limit then
                return 0
            end
            local sequence = redis.call('INCR', KEYS[2])
            redis.call('ZADD', KEYS[1], now, now .. '-' .. sequence)
            redis.call('PEXPIRE', KEYS[1], window)
            redis.call('PEXPIRE', KEYS[2], window)
            return 1
            """, Long.class);

    private final Map<String, Deque<Long>> localWindows = new ConcurrentHashMap<>();
    private final AtomicLong lastFallbackWarning = new AtomicLong();
    /**
     * After a failure Redis is skipped for this long, so an outage does not add the Redis timeout to every request.
     */
    private long redisRetryIntervalMillis = 5_000L;
    private volatile long skipRedisUntil;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void doRateLimit(String resourceKey) {
        String key = "rate-limit:" + resourceKey;
        long now = System.currentTimeMillis();
        boolean allowed = now < skipRedisUntil ? allowLocally(key, now) : allowThroughRedis(key, now);
        if (!allowed) {
            // A distinct code lets the frontend tell "slow down" apart from a server failure.
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "Too many requests. Please try again shortly.");
        }
    }

    private boolean allowThroughRedis(String key, long now) {
        try {
            Long result = stringRedisTemplate.execute(
                    SLIDING_WINDOW_SCRIPT,
                    List.of(key, key + ":sequence"),
                    String.valueOf(now),
                    String.valueOf(WINDOW_MILLIS),
                    String.valueOf(MAX_REQUESTS)
            );
            // Redis is back: drop the fallback windows instead of letting them pile up.
            if (!localWindows.isEmpty()) {
                localWindows.clear();
            }
            return Long.valueOf(1L).equals(result);
        } catch (DataAccessException redisUnavailable) {
            skipRedisUntil = now + redisRetryIntervalMillis;
            warnAboutFallback(redisUnavailable);
            return allowLocally(key, now);
        }
    }

    /**
     * The same sliding window as the Redis script, kept in this JVM.
     */
    private boolean allowLocally(String key, long now) {
        boolean[] allowed = {false};
        localWindows.compute(key, (ignored, window) -> {
            Deque<Long> requests = window == null ? new ArrayDeque<>() : window;
            while (!requests.isEmpty() && requests.peekFirst() <= now - WINDOW_MILLIS) {
                requests.pollFirst();
            }
            if (requests.size() < MAX_REQUESTS) {
                requests.addLast(now);
                allowed[0] = true;
            }
            return requests;
        });
        return allowed[0];
    }

    private void warnAboutFallback(DataAccessException exception) {
        long now = System.currentTimeMillis();
        long last = lastFallbackWarning.get();
        if (now - last >= FALLBACK_WARNING_INTERVAL_MILLIS && lastFallbackWarning.compareAndSet(last, now)) {
            log.warn("Redis is unavailable; rate limiting falls back to this instance until it recovers: {}",
                    exception.getMessage());
        }
    }
}
