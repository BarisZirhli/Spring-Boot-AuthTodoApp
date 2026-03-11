package com.example.authcrud.service;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RefreshTokenStoreService {

    private final StringRedisTemplate redisTemplate;

    public RefreshTokenStoreService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void store(String username, String tokenId, long ttlMillis) {
        String key = key(username, tokenId);
        redisTemplate.opsForValue().set(key, "1", Duration.ofMillis(ttlMillis));
    }

    public boolean isActive(String username, String tokenId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(username, tokenId)));
    }

    public void revoke(String username, String tokenId) {
        redisTemplate.delete(key(username, tokenId));
    }

    private String key(String username, String tokenId) {
        return "refresh:" + username + ":" + tokenId;
    }
}
