package com.zachary.BI.manager;

import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.exception.BusinessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;

@Service
public class RedisRateLimitManager {

    private static final int MAX_REQUESTS = 2;
    private static final long WINDOW_MILLIS = 1_000L;
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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void doRateLimit(String resourceKey) {
        String key = "rate-limit:" + resourceKey;
        Long allowed = stringRedisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                List.of(key, key + ":sequence"),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(WINDOW_MILLIS),
                String.valueOf(MAX_REQUESTS)
        );
        if (!Long.valueOf(1L).equals(allowed)) {
            // A distinct code lets the frontend tell "slow down" apart from a server failure.
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "Too many requests. Please try again shortly.");
        }
    }
}
