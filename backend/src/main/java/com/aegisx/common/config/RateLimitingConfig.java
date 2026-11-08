package com.aegisx.common.config;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Refill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting configuration to prevent brute force attacks.
 * 
 * Policies:
 * - Login/Register: 5 requests per minute per IP
 * - General API: 100 requests per minute per IP
 * 
 * Benefits:
 * - Prevents credential brute force (5 tries/min = reasonable for users, blocks bots)
 * - Mitigates DoS attacks
 * - Protects payment webhooks from replay
 * 
 * This implementation is single-instance only: buckets live in this JVM, so each additional
 * application instance receives its own full allowance. Use a shared store before scaling out.
 *
 * Production Recommendation:
 * - Replace in-memory implementation with Redis for distributed rate limiting
 * - Use IP from X-Forwarded-For header when behind reverse proxy
 */
@Slf4j
@Configuration
public class RateLimitingConfig implements WebMvcConfigurer {

    private final Map<String, Bucket> cache = new ConcurrentHashMap<>();

    /**
     * Whether the client address may be taken from {@code X-Forwarded-For} / {@code X-Real-IP}.
     *
     * <p>Off by default. Both headers are supplied by the caller unless a trusted reverse proxy
     * overwrites them, so trusting them by default let anyone bypass the login limiter by sending a
     * different value on each request — the limiter would create a fresh bucket every time. Turn it
     * on only when the application is deployed behind a proxy that replaces the header.</p>
     */
    @Value("${aegisx.rate-limit.trust-forwarded-headers:false}")
    private boolean trustForwardedHeaders;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitingInterceptor(cache, trustForwardedHeaders));
    }

    /**
     * Rate limiting interceptor using Token Bucket algorithm.
     */
    @Slf4j
    static class RateLimitingInterceptor implements HandlerInterceptor {

        /** Idle buckets are dropped beyond this size so a large client population cannot grow the map forever. */
        private static final int MAX_TRACKED_BUCKETS = 10_000;

        private final Map<String, Bucket> bucketCache;
        private final boolean trustForwardedHeaders;

        RateLimitingInterceptor(Map<String, Bucket> bucketCache, boolean trustForwardedHeaders) {
            this.bucketCache = bucketCache;
            this.trustForwardedHeaders = trustForwardedHeaders;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            String clientIp = getClientIp(request);
            String path = request.getRequestURI();

            // Apply strict rate limiting to auth endpoints
            if (isAuthEndpoint(path)) {
                return checkRateLimit(response, clientIp, path, 5, 60); // 5 req/min
            }

            // Apply general rate limiting to all other endpoints
            if (path.startsWith("/api/")) {
                return checkRateLimit(response, clientIp, path, 100, 60); // 100 req/min
            }

            return true;
        }

        /**
         * Check rate limit and respond with 429 if exceeded.
         */
        private boolean checkRateLimit(HttpServletResponse response, String clientIp, String path, int capacity, int seconds) throws Exception {
            String key = clientIp + ":" + path;
            Bucket bucket = bucketCache.computeIfAbsent(key, k -> createBucket(capacity, seconds));

            if (bucketCache.size() > MAX_TRACKED_BUCKETS) {
                // A full bucket carries no history worth keeping, so forgetting it cannot raise a
                // later client's allowance above the configured rate.
                bucketCache.entrySet().removeIf(entry -> entry.getValue().getAvailableTokens() >= capacity);
            }

            if (bucket.tryConsume(1)) {
                return true;
            } else {
                log.warn("Rate limit exceeded: {} on {}", clientIp, path);
                response.setStatus(429); // Too Many Requests
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Rate limit exceeded. Please try again later.\"}");
                return false;
            }
        }

        /**
         * Create a new token bucket with specified capacity and refill duration.
         */
        private Bucket createBucket(int capacity, int seconds) {
            return Bucket4j.builder()
                .addLimit(Bandwidth.classic(capacity, Refill.intervally(capacity, Duration.ofSeconds(seconds))))
                .build();
        }

        /**
         * Check if path is an authentication endpoint.
         */
        private boolean isAuthEndpoint(String path) {
            return path.contains("/api/auth/login") || path.contains("/api/auth/register");
        }

        /**
         * Extract client IP from request, accounting for proxy headers.
         */
        private String getClientIp(HttpServletRequest request) {
            if (!trustForwardedHeaders) {
                // The only address the server can vouch for. A header-based value is attacker input
                // until a trusted proxy is known to replace it.
                return request.getRemoteAddr();
            }

            // Check for X-Forwarded-For header (behind reverse proxy)
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                return xForwardedFor.split(",")[0].trim();
            }

            // Check for X-Real-IP header (nginx)
            String xRealIp = request.getHeader("X-Real-IP");
            if (xRealIp != null && !xRealIp.isEmpty()) {
                return xRealIp;
            }

            // Fall back to remote address
            return request.getRemoteAddr();
        }
    }
}
