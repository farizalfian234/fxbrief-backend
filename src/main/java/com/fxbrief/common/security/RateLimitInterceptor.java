package com.fxbrief.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.dto.ApiError;
import com.fxbrief.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        Map<String, Integer> limits = properties.endpointLimits();
        if (limits == null || limits.isEmpty()) {
            return true;
        }

        String path = request.getRequestURI();
        Integer limit = limits.get(path);
        if (limit == null) {
            return true;
        }

        String clientIp = resolveClientIp(request);
        String key = path + ":" + clientIp;
        RateLimiter.Decision decision = rateLimiter.check(key, limit);

        if (decision.allowed()) {
            return true;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        ApiError error = ApiError.of(
                ErrorCodes.RATE_LIMIT_EXCEEDED,
                "Too many requests. Please try again later.");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(error));
        return false;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
