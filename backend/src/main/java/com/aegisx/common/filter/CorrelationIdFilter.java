package com.aegisx.common.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that generates and injects correlation IDs into all requests.
 * 
 * A correlation ID is a unique identifier for a single request that gets propagated
 * throughout the entire request lifecycle across all modules. This enables tracing
 * a single merchant request from entry to all downstream services:
 * 
 * Request Flow with Correlation ID:
 * 1. HTTP request arrives → CorrelationIdFilter generates/extracts X-Correlation-ID
 * 2. MDC (Mapped Diagnostic Context) stores the ID for structured logging
 * 3. TransactionService.createTransaction() records audit event with correlationId
 * 4. Razorpay webhook arrives → same correlationId propagated in webhook event
 * 5. DocumentService.upload() uses same correlationId for OCR audit trail
 * 6. OCRService.process() uses same correlationId for AI audit trail
 * 7. SpringAIService.analyze() uses same correlationId for policy audit trail
 * 8. PolicyEngine.evaluate() uses same correlationId for final decision
 * 9. All events queried via GET /api/transactions/{id}/audit can be traced by correlationId
 * 
 * This filter ensures:
 * - Single request ID across all modules
 * - Structured logging includes correlationId automatically via MDC
 * - Audit trail is fully traceable end-to-end
 * - No manual correlation ID passing required
 * - Distributed tracing-friendly (correlationId can be logged by infrastructure)
 * 
 * Design Pattern: Servlet Filter + MDC (Mapped Diagnostic Context)
 * Rationale: MDC is the Spring/SLF4J standard for request-scoped context.
 *           Filter ensures every request has a correlation ID before business logic runs.
 *           No invasive method parameters required—all layers can access via MDC.
 * 
 * MDC Access in Any Service:
 * String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY);
 * 
 * Logback Configuration Example:
 * <pattern>%d{yyyy-MM-dd HH:mm:ss} [%X{correlationId}] %-5level %logger{36} - %msg%n</pattern>
 * This pattern will automatically include the correlation ID in every log line.
 */
@Component
public class CorrelationIdFilter implements Filter {

    /**
     * MDC key for storing the correlation ID.
     * Used to retrieve correlation ID in services via: MDC.get(CORRELATION_ID_KEY)
     */
    public static final String CORRELATION_ID_KEY = "correlationId";

    /**
     * HTTP header name for correlation ID.
     * Clients can pass X-Correlation-ID header. If missing, filter generates a new UUID.
     */
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    /**
     * Apply correlation ID to this request.
     * 
     * Algorithm:
     * 1. Check if X-Correlation-ID header exists
     * 2. If yes, use it (enables tracing across service boundaries)
     * 3. If no, generate new UUID (ensures every request has a unique ID)
     * 4. Store in MDC for structured logging
     * 5. Continue filter chain
     * 6. Clean up MDC after response
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {

        // Get or generate correlation ID
        String correlationId = getCorrelationId((HttpServletRequest) request);

        // Store in MDC for structured logging
        MDC.put(CORRELATION_ID_KEY, correlationId);

        try {
            // Continue filter chain with correlation ID in MDC
            chain.doFilter(request, response);
        } finally {
            // Clean up MDC to prevent memory leaks in thread pools
            MDC.remove(CORRELATION_ID_KEY);
        }
    }

    /**
     * Extract correlation ID from request header or generate a new one.
     *
     * @param request the HTTP request
     * @return the correlation ID (from header or newly generated UUID)
     */
    private String getCorrelationId(HttpServletRequest request) {
        // Check if header exists
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);

        // If not provided, generate a new UUID
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        return correlationId;
    }
}
