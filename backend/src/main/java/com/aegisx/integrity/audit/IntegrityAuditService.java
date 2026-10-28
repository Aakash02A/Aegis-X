package com.aegisx.integrity.audit;

import com.aegisx.integrity.entity.IntegrityAuditEvent;
import com.aegisx.integrity.repository.IntegrityAuditEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Module H: append-only audit trail for the integrity engine.
 *
 * <p>Writes are {@code REQUIRES_NEW} so an audit record survives even when the business
 * transaction that produced it rolls back — a failed state change is exactly the kind of event an
 * auditor wants to see.</p>
 *
 * <p>Every detail payload passes through {@link #redact(String)} before it is stored or logged.
 * Aegis-X does not claim this table is tamper-proof; it claims it is append-only by construction and
 * that it contains no credentials.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrityAuditService {

    /** JSON keys whose values must never be persisted or printed. */
    private static final Pattern SENSITIVE_JSON = Pattern.compile(
            "(\"(?:authorization|key_secret|api_key|apikey|password|passwd|secret|token|access_token|webhook_secret)\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    private static final String CORRELATION_KEY = "correlationId";

    private final IntegrityAuditEventRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String eventType,
                       String category,
                       String actorLabel,
                       UUID workspaceId,
                       Map<String, Object> detail,
                       UUID orderId,
                       UUID paymentId,
                       UUID refundId,
                       UUID transferId,
                       UUID exceptionId,
                       UUID runId,
                       UUID actorId,
                       boolean simulated) {
        IntegrityAuditEvent event = IntegrityAuditEvent.builder()
                .workspaceId(workspaceId)
                .eventType(eventType)
                .category(category)
                .actorLabel(actorLabel == null ? "SYSTEM" : actorLabel)
                .actorId(actorId)
                .orderId(orderId)
                .paymentId(paymentId)
                .refundId(refundId)
                .transferId(transferId)
                .exceptionId(exceptionId)
                .runId(runId)
                .correlationId(MDC.get(CORRELATION_KEY))
                .detailJson(detail == null ? null : redact(toJson(detail)))
                .simulated(simulated)
                .occurredAt(Instant.now())
                .build();
        repository.save(event);
        log.info("Integrity audit: type={} category={} actor={} workspace={}",
                eventType, category, event.getActorLabel(), workspaceId);
    }

    public void recordSystemEvent(String eventType, String category, UUID workspaceId,
                                  Map<String, Object> detail, boolean simulated) {
        record(eventType, category, "SYSTEM", workspaceId, detail, null, null, null, null, null, null, null, simulated);
    }

    public String toJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("serializationError", e.getClass().getSimpleName());
            fallback.put("keys", detail.keySet());
            return fallback.toString();
        }
    }

    /** Replaces the value of any sensitive JSON key with a fixed marker. */
    public String redact(String json) {
        if (json == null) {
            return null;
        }
        return SENSITIVE_JSON.matcher(json).replaceAll("$1\"[REDACTED]\"");
    }
}
