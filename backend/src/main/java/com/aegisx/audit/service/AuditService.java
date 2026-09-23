package com.aegisx.audit.service;

import com.aegisx.audit.entity.ActorType;
import com.aegisx.audit.entity.AuditEvent;
import com.aegisx.audit.entity.AuditEventType;
import com.aegisx.audit.entity.AuditModule;
import com.aegisx.audit.repository.AuditEventRepository;
import com.aegisx.transaction.entity.Transaction;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Centralized service for recording audit events.
 * 
 * This is the single point of entry for all modules to record immutable audit events.
 * Instead of modules creating AuditEvent entities directly (tight coupling),
 * they call methods on this service with simple parameters.
 * 
 * The service ensures:
 * - Consistent persistence semantics
 * - Proper transaction handling
 * - Null safety
 * - Immutability enforcement
 * - Structured logging integration
 * 
 * Design Pattern: Facade
 * Rationale: Simplifies audit integration for modules.
 *           Each module calls auditService.record(...) once per significant event.
 *           The service handles persistence, validation, and logging.
 * 
 * Usage Examples:
 * 
 * // Record a transaction state transition
 * auditService.recordStateTransition(
 *     transaction,
 *     AuditEventType.PAYMENT_RECEIVED,
 *     AuditModule.RAZORPAY,
 *     null, // system event
 *     ActorType.WEBHOOK,
 *     correlationId,
 *     "CREATED",
 *     "PAYMENT_RECEIVED",
 *     eventData
 * );
 * 
 * // Record policy engine approval
 * auditService.recordPolicyDecision(
 *     transaction,
 *     AuditEventType.POLICY_APPROVED,
 *     correlationId,
 *     "POLICY_EVALUATION",
 *     "APPROVED",
 *     rulesEvaluated,
 *     "APPROVED",
 *     "All policy rules satisfied"
 * );
 * 
 * // Record AI analysis
 * auditService.recordAIAnalysis(
 *     transaction,
 *     correlationId,
 *     "MEDIUM",
 *     "High transaction velocity detected",
 *     0.92,
 *     additionalContext
 * );
 */
@Slf4j
@Service
@AllArgsConstructor
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    /**
     * Record a state transition audit event.
     * Used when a transaction's status changes.
     *
     * @param transaction the transaction
     * @param eventType the event type
     * @param module the source module
     * @param actorId the actor's UUID (null for system events)
     * @param actorType the type of actor
     * @param correlationId the request correlation ID
     * @param previousState the previous status
     * @param newState the new status
     * @param eventData additional context
     */
    @Transactional
    public void recordStateTransition(
        Transaction transaction,
        AuditEventType eventType,
        AuditModule module,
        UUID actorId,
        ActorType actorType,
        String correlationId,
        String previousState,
        String newState,
        JsonNode eventData
    ) {
        AuditEvent event = AuditEventFactory.createStateTransitionEvent(
            transaction,
            eventType,
            module,
            actorId,
            actorType,
            correlationId,
            previousState,
            newState,
            eventData
        );

        auditEventRepository.save(event);

        log.info(
            "Audit: Transaction {} transitioned {} -> {} via {} (correlationId: {})",
            transaction.getReferenceNumber(),
            previousState,
            newState,
            module,
            correlationId
        );
    }

    /**
     * Record a system event (non-state-transition).
     * Used for actions like document uploads, OCR completion, AI analysis.
     *
     * @param transaction the transaction
     * @param eventType the event type
     * @param module the source module
     * @param actorId the actor's UUID (null for system events)
     * @param actorType the type of actor
     * @param correlationId the request correlation ID
     * @param eventData additional context
     */
    @Transactional
    public void recordSystemEvent(
        Transaction transaction,
        AuditEventType eventType,
        AuditModule module,
        UUID actorId,
        ActorType actorType,
        String correlationId,
        JsonNode eventData
    ) {
        AuditEvent event = AuditEventFactory.createSystemEvent(
            transaction,
            eventType,
            module,
            actorId,
            actorType,
            correlationId,
            eventData
        );

        auditEventRepository.save(event);

        log.info(
            "Audit: Transaction {} - {} event from {} (correlationId: {})",
            transaction.getReferenceNumber(),
            eventType,
            module,
            correlationId
        );
    }

    /**
     * Record an error/failure event.
     * Used when a module encounters an exception.
     *
     * @param transaction the transaction
     * @param eventType the error event type
     * @param module the source module
     * @param correlationId the request correlation ID
     * @param errorMessage the error message
     * @param exceptionClass the exception class name
     * @param stackTrace partial stack trace
     */
    @Transactional
    public void recordErrorEvent(
        Transaction transaction,
        AuditEventType eventType,
        AuditModule module,
        String correlationId,
        String errorMessage,
        String exceptionClass,
        String stackTrace
    ) {
        AuditEvent event = AuditEventFactory.createErrorEvent(
            transaction,
            eventType,
            module,
            correlationId,
            errorMessage,
            exceptionClass,
            stackTrace
        );

        auditEventRepository.save(event);

        log.warn(
            "Audit: Transaction {} - ERROR event from {} - {} (correlationId: {})",
            transaction.getReferenceNumber(),
            module,
            errorMessage,
            correlationId
        );
    }

    /**
     * Record a webhook reception event.
     *
     * @param transaction the transaction
     * @param eventType the webhook event type
     * @param module the webhook source module
     * @param correlationId the request correlation ID
     * @param webhookEventId the webhook event ID from external system
     * @param signatureVerified whether the webhook signature was verified
     * @param payload the webhook payload
     */
    @Transactional
    public void recordWebhookEvent(
        Transaction transaction,
        AuditEventType eventType,
        AuditModule module,
        String correlationId,
        String webhookEventId,
        String signatureVerified,
        JsonNode payload
    ) {
        AuditEvent event = AuditEventFactory.createWebhookEvent(
            transaction,
            eventType,
            module,
            correlationId,
            webhookEventId,
            signatureVerified,
            payload
        );

        auditEventRepository.save(event);

        log.info(
            "Audit: Transaction {} - webhook {} from {} (correlationId: {})",
            transaction.getReferenceNumber(),
            webhookEventId,
            module,
            correlationId
        );
    }

    /**
     * Record a user action event.
     *
     * @param transaction the transaction
     * @param eventType the event type
     * @param module the source module
     * @param userId the user's UUID
     * @param correlationId the request correlation ID
     * @param eventData additional context
     */
    @Transactional
    public void recordUserEvent(
        Transaction transaction,
        AuditEventType eventType,
        AuditModule module,
        UUID userId,
        String correlationId,
        JsonNode eventData
    ) {
        AuditEvent event = AuditEventFactory.createUserEvent(
            transaction,
            eventType,
            module,
            userId,
            correlationId,
            eventData
        );

        auditEventRepository.save(event);

        log.info(
            "Audit: Transaction {} - user event {} by {} (correlationId: {})",
            transaction.getReferenceNumber(),
            eventType,
            userId,
            correlationId
        );
    }

    /**
     * Record an AI analysis event.
     *
     * @param transaction the transaction
     * @param eventType the AI event type
     * @param correlationId the request correlation ID
     * @param riskAssessment the risk level
     * @param reasoning the AI's reasoning
     * @param confidence the confidence score
     * @param additionalData any additional analysis data
     */
    @Transactional
    public void recordAIAnalysis(
        Transaction transaction,
        AuditEventType eventType,
        String correlationId,
        String riskAssessment,
        String reasoning,
        Double confidence,
        JsonNode additionalData
    ) {
        AuditEvent event = AuditEventFactory.createAIEvent(
            transaction,
            eventType,
            correlationId,
            riskAssessment,
            reasoning,
            confidence,
            additionalData
        );

        auditEventRepository.save(event);

        log.info(
            "Audit: Transaction {} - AI analysis: risk={}, confidence={} (correlationId: {})",
            transaction.getReferenceNumber(),
            riskAssessment,
            confidence,
            correlationId
        );
    }

    /**
     * Record a policy engine decision event.
     *
     * @param transaction the transaction
     * @param eventType the policy event type
     * @param correlationId the request correlation ID
     * @param previousState the state before policy evaluation
     * @param newState the state after policy evaluation
     * @param rulesEvaluated the rules that were evaluated
     * @param decision the decision (APPROVED, HOLD, REJECTED)
     * @param justification the human-readable justification
     */
    @Transactional
    public void recordPolicyDecision(
        Transaction transaction,
        AuditEventType eventType,
        String correlationId,
        String previousState,
        String newState,
        JsonNode rulesEvaluated,
        String decision,
        String justification
    ) {
        AuditEvent event = AuditEventFactory.createPolicyEvent(
            transaction,
            eventType,
            correlationId,
            previousState,
            newState,
            rulesEvaluated,
            decision,
            justification
        );

        auditEventRepository.save(event);

        log.info(
            "Audit: Transaction {} - policy decision: {} - {} (correlationId: {})",
            transaction.getReferenceNumber(),
            decision,
            justification,
            correlationId
        );
    }
}
