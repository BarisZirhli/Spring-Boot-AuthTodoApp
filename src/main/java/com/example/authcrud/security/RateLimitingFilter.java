package com.example.authcrud.security;

import com.example.authcrud.dto.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;
    private final Map<String, Deque<Long>> loginAttemptsByClient = new ConcurrentHashMap<>();
    private final int loginMaxAttempts;
    private final long loginWindowMillis;
    private final int loginWindowSeconds;

    public RateLimitingFilter(ObjectMapper objectMapper,
                              @Value("${security.rate-limit.login.max-attempts}") int loginMaxAttempts,
                              @Value("${security.rate-limit.login.window-seconds}") int loginWindowSeconds) {
        this.objectMapper = objectMapper;
        this.loginMaxAttempts = loginMaxAttempts;
        this.loginWindowMillis = loginWindowSeconds * 1000L;
        this.loginWindowSeconds = loginWindowSeconds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && "/api/auth/login".equals(request.getServletPath()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String clientKey = resolveClientKey(request);
        Deque<Long> attempts = loginAttemptsByClient.computeIfAbsent(clientKey, key -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        boolean allowed;

        synchronized (attempts) {
            long threshold = now - loginWindowMillis;
            while (!attempts.isEmpty() && attempts.peekFirst() < threshold) {
                attempts.removeFirst();
            }

            if (attempts.size() >= loginMaxAttempts) {
                allowed = false;
            } else {
                attempts.addLast(now);
                allowed = true;
            }
        }

        if (!allowed) {
            ApiErrorResponse errorResponse = new ApiErrorResponse(
                    Instant.now(),
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                    "Too many login attempts. Please try again later.",
                    request.getRequestURI(),
                    null
            );

            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(loginWindowSeconds));
            objectMapper.writeValue(response.getOutputStream(), errorResponse);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientKey(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
