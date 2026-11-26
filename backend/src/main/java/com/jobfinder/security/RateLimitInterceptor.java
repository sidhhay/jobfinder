package com.jobfinder.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed-window, in-memory rate limiter for methods annotated with {@link RateLimit}. */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private record Window(long minute, int count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RateLimit limit = method.getMethodAnnotation(RateLimit.class);
        if (limit == null) {
            return true;
        }
        long minute = System.currentTimeMillis() / 60_000;
        String key = request.getRemoteAddr() + "|" + method.getMethod();
        Window window = windows.merge(key, new Window(minute, 1),
                (old, fresh) -> old.minute() == minute ? new Window(minute, old.count() + 1) : fresh);
        if (window.count() <= limit.perMinute()) {
            return true;
        }
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"detail\":\"Rate limit exceeded: " + limit.perMinute() + " per 1 minute\"}");
        return false;
    }
}
