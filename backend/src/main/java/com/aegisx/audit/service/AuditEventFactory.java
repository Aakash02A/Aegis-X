package com.aegisx.audit.service;

import com.aegisx.audit.entity.ActorType;
import com.aegisx.audit.entity.AuditEvent;
import com.aegisx.audit.entity.AuditEventType;
import com.aegisx.audit.entity.AuditModule;
import com.aegisx.transaction.entity.Transaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Factory for creating standardized audit events.
 * 
 * This factory ensures consistent audit event creation across all modules
 * without requiring modules to understand the AuditEvent entity structure.
 * 
 * Every module calls AuditService.record(...) which uses this factory
 * to build the audit event, ensuring:
 * - Consistent timestamp handling (Instant.now())
 * - Proper UUID generation
 * - Standardized event_data JSONB serialization
 * - Null safety for optional fields
 * 
 * Design Pattern: Builder Factory
 * Rationale: Prevents tight coupling between modules and audit domain.
 *           Each module calls auditService.record(...) with simple parameters.
 *           The factory and service handle all domain logic.
 */
@NoArgsConstructor
public final class AuditEventFactory {

    private static final JsonNodeFactory nodeFactory = JsonNodeFactory.instance;

    /**
     * Create a state transition audit event.
     * Used for recording status changes in transactions.
     *
     * @param transaction the transaction being transitioned
     * @param eventType the event type (e.g., PAYMENT_RECEIVED, POLICY_APPROVED)
     * @param module the source module
     * @param actorId the actor's UUID (or null for system events)
     * @param actorType the type of actor
     * @param correlationId the request correlation ID
     * @param previousState the previous transaction status
     * @param newState the new transaction status
     * @param eventData additional context as JSONB
     * @return a new, unsaved AuditEvent
     */
    public static AuditEvent createStateTransitionEvent(
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
        return AuditEvent.builder()
            .id(UUID.randomUUID())
            .transaction(transaction)
            .eventType(eventType)
            .module(module)
            .actorId(actorId)
            .actorType(actorType)
            .correlationId(correlationId)
            .previousState(previousState)
            .newState(newState)
            .eventData(eventData)
            .createdAt(Instant.now())
            .build();
    }

    /**
     * Create a non-state-transition audit event.
     * Used for recording actions that don't change transaction status
     * (e.g., document upload, OCR completion, policy evaluation).
     *
     * @param transaction the transaction
     * @param eventType the event type (e.g., OCR_COMPLETED, AI_ANALYSIS_COMPLETED)
     * @param module the source module
     * @param actorId the actor's UUID (or null for system events)
     * @param actorType the type of actor
     * @param correlationId the request correlation ID
     * @param eventData additional context as JSONB
     * @return a new, unsaved AuditEvent
     */
    public static AuditEvent createSystemEvent(
        Transaction transaction,
        AuditEventType eventType,
        AuditModule module,
        UUID actorId,
        ActorType actorType,
        String correlationId,
        JsonNode eventData
    ) {
        return AuditEvent.builder()
            .id(UUID.randomUUID())
            .transaction(transaction)
            .eventType(eventType)
            .module(module)
            .actorId(actorId)
            .actorType(actorType)
            .correlationId(correlationId)
            .previousState(null)
            .newState(null)
            .eventData(eventData)
            .createdAt(Instant.now())
            .build();
    }

    /**
     * Create an audit event for an error or failure.
     * Used when a module encounters an exception that should be audited.
     *
     * @param transaction the transaction
     * @param eventType the error event type (e.g., OCR_FAILED, AI_ANALYSIS_FAILED)
     * @param module the source module
     * @param correlationId the request correlation ID
     * @param errorMessage human-readable error description
     * @param exceptionClass the exception class name (for debugging)
     * @param stackTrace partial stack trace (first few lines)
     * @return a new, unsaved AuditEvent
     */
    public static AuditEvent createErrorEvent(
        Transaction transaction,
        AuditEventType eventType,
        AuditModule module,
        String correlationId,
        String errorMessage,
        String exceptionClass,
        String stackTrace
    ) {
        ObjectNode eventData = nodeFactory.objectNode();
        eventData.put("error", errorMessage);
        eventData.put("exceptionClass", exceptionClass);
        eventData.put("stackTrace", stackTrace);

        return AuditEvent.builder()
            .id(UUID.randomUUID())
            .transaction(transaction)
            .eventType(eventType)
            .module(module)
            .actorId(null)
            .actorType(ActorType.SYSTEM)
            .correlationId(correlationId)
            .previousState(null)
            .newState(null)
            .eventData(eventData)
            .createdAt(Instant.now())
            .build();
    }

    /**
     * Create an audit event for webhook reception.
     * Used when an external service (Razorpay, WhatsApp, etc.) sends an event.
     *
     * @param transaction the transaction
     * @param eventType the webhook event type (e.g., WEBHOOK_RECEIVED, PAYMENT_RECEIVED)
     * @param module the webhook source module
     * @param correlationId the request correlation ID
     * @param webhookEventId the webhook's event ID (from external system)
     * @param signature the webhook signature verification status
     * @param payload the webhook payload as JSONB
     * @return a new, unsaved AuditEvent
     */
    public static AuditEvent createWebhookEvent(
        Transaction transaction,
        AuditEventType eventType,
        AuditModule module,
        String correlationId,
        String webhookEventId,
        String signature,
        JsonNode payload
    ) {
        ObjectNode eventData = (ObjectNode) (payload != null ? payload : nodeFactory.objectNode());
        eventData.put("webhookEventId", webhookEventId);
        eventData.put("signatureVerified", signature);

        return AuditEvent.builder()
            .id(UUID.randomUUID())
            .transaction(transaction)
            .eventType(eventType)
            .module(module)
            .actorId(null)
            .actorType(ActorType.WEBHOOK)
            .correlationId(correlationId)
            .previousState(null)
            .newState(null)
            .eventData(eventData)
            .createdAt(Instant.now())
            .build();
    }

    /**
     * Create an audit event for user actions.
     * Used when a human user (merchant, compliance officer) performs an action.
     *
     * @param transaction the transaction
     * @param eventType the event type (e.g., DOCUMENT_UPLOADED, USER_REGISTERED)
     * @param module the source module
     * @param userId the user's UUID
     * @param correlationId the request correlation ID
     * @param eventData additional context as JSONB
     * @return a new, unsaved AuditEvent
     */
    public static AuditEvent createUserEvent(
        Transaction transaction,
        AuditEventType eventType,
        AuditModule module,
        UUID userId,
        String correlationId,
        JsonNode eventData
    ) {
        return AuditEvent.builder()
            .id(UUID.randomUUID())
            .transaction(transaction)
            .eventType(eventType)
            .module(module)
            .actorId(userId)
            .actorType(ActorType.USER)
            .correlationId(correlationId)
            .previousState(null)
            .newState(null)
            .eventData(eventData)
            .createdAt(Instant.now())
            .build();
    }

    /**
     * Create an audit event for AI-generated insights.
     * Used when Spring AI completes regulatory analysis.
     *
     * @param transaction the transaction
     * @param eventType the AI event type (e.g., AI_ANALYSIS_COMPLETED)
     * @param correlationId the request correlation ID
     * @param riskAssessment the regulatory risk level (LOW, MEDIUM, HIGH, CRITICAL)
     * @param reasoning the AI's regulatory reasoning
     * @param confidence the confidence score (0.0-1.0)
     * @param additionalData any additional analysis data
     * @return a new, unsaved AuditEvent
     */
    public static AuditEvent createAIEvent(
        Transaction transaction,
        AuditEventType eventType,
        String correlationId,
        String riskAssessment,
        String reasoning,
        Double confidence,
        JsonNode additionalData
    ) {
        ObjectNode eventData = (ObjectNode) (additionalData != null ? additionalData : nodeFactory.objectNode());
        eventData.put("riskAssessment", riskAssessment);
        eventData.put("reasoning", reasoning);
        eventData.put("confidence", confidence);

        return AuditEvent.builder()
            .id(UUID.randomUUID())
            .transaction(transaction)
            .eventType(eventType)
            .module(AuditModule.AI)
            .actorId(null)
            .actorType(ActorType.AI)
            .correlationId(correlationId)
            .previousState(null)
            .newState(null)
            .eventData(eventData)
            .createdAt(Instant.now())
            .build();
    }

    /**
     * Create an audit event for policy engine decisions.
     * Used when Java Policy Engine makes a compliance decision.
     *
     * @param transaction the transaction
     * @param eventType the policy event type (e.g., POLICY_APPROVED, POLICY_HOLD)
     * @param correlationId the request correlation ID
     * @param previousState the state before policy evaluation
     * @param newState the state after policy evaluation
     * @param rulesEvaluated the policy rules that were evaluated
     * @param decision the decision (APPROVED, HOLD, REJECTED)
     * @param justification the human-readable justification
     * @return a new, unsaved AuditEvent
     */
    public static AuditEvent createPolicyEvent(
        Transaction transaction,
        AuditEventType eventType,
        String correlationId,
        String previousState,
        String newState,
        JsonNode rulesEvaluated,
        String decision,
        String justification
    ) {
        ObjectNode eventData = nodeFactory.objectNode();
        eventData.put("decision", decision);
        eventData.put("justification", justification);
        eventData.set("rulesEvaluated", rulesEvaluated != null ? rulesEvaluated : nodeFactory.arrayNode());

        return AuditEvent.builder()
            .id(UUID.randomUUID())
            .transaction(transaction)
            .eventType(eventType)
            .module(AuditModule.POLICY_ENGINE)
            .actorId(null)
            .actorType(ActorType.POLICY_ENGINE)
            .correlationId(correlationId)
            .previousState(previousState)
            .newState(newState)
            .eventData(eventData)
            .createdAt(Instant.now())
            .build();
    }

    /**
     * Helper to create a JSONB object node for event data.
     * Useful for modules building custom event data.
     *
     * @return a new ObjectNode ready for field additions
     */
    public static ObjectNode createEventDataNode() {
        return nodeFactory.objectNode();
    }
}
