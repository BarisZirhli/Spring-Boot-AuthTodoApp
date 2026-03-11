package com.example.authcrud.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class LoginAttemptService {

    private final StringRedisTemplate redisTemplate;
    private final int maxFailures;
    private final int windowSeconds;
    private final int lockSeconds;

    public LoginAttemptService(StringRedisTemplate redisTemplate,
                               @Value("${security.bruteforce.max-failures}") int maxFailures,
                               @Value("${security.bruteforce.window-seconds}") int windowSeconds,
                               @Value("${security.bruteforce.lock-seconds}") int lockSeconds) {
        this.redisTemplate = redisTemplate;
        this.maxFailures = maxFailures;
        this.windowSeconds = windowSeconds;
        this.lockSeconds = lockSeconds;
    }

    public boolean isLocked(String username, String clientKey) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(lockKey(username, clientKey)));
    }

    public boolean recordFailedAttempt(String username, String clientKey) {
        String failKey = failKey(username, clientKey);
        Long attempts = redisTemplate.opsForValue().increment(failKey);
        if (attempts != null && attempts == 1L) {
            redisTemplate.expire(failKey, Duration.ofSeconds(windowSeconds));
        }

        if (attempts != null && attempts >= maxFailures) {
            redisTemplate.opsForValue().set(lockKey(username, clientKey), "1", Duration.ofSeconds(lockSeconds));
            redisTemplate.delete(failKey);
            return true;
        }

        return false;
    }

    public void clearAttempts(String username, String clientKey) {
        redisTemplate.delete(failKey(username, clientKey));
    }

    private String failKey(String username, String clientKey) {
        return "auth:fail:" + username + ":" + clientKey;
    }

    private String lockKey(String username, String clientKey) {
        return "auth:lock:" + username + ":" + clientKey;
    }
}
