package com.fxbrief.common.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimiter {

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Duration windowDuration;

    public RateLimiter(RateLimitProperties properties) {
        this.windowDuration = Duration.ofSeconds(properties.windowSeconds());
    }

    public Decision check(String key, int limit) {
        Instant now = Instant.now();
        Window window = windows.compute(key, (k, existing) -> {
            if (existing == null || now.isAfter(existing.expiresAt)) {
                return new Window(new AtomicInteger(0), now.plus(windowDuration));
            }
            return existing;
        });

        int count = window.counter.incrementAndGet();
        long retryAfterSeconds = Math.max(1, Duration.between(now, window.expiresAt).getSeconds());

        if (count > limit) {
            return new Decision(false, retryAfterSeconds);
        }
        return new Decision(true, retryAfterSeconds);
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private record Window(AtomicInteger counter, Instant expiresAt) {}
}
