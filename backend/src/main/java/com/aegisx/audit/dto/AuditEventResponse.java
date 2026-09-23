package com.aegisx.audit.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a single audit event.
 * Used in audit timeline and search APIs.
 * Contains all immutable audit event information without exposing database entities.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEventResponse {

    /**
     * Unique identifier for this audit event.
     */
    private UUID id;

    /**
     * ID of the transaction this event belongs to.
     */
    private UUID transactionId;

    /**
     * Type of business event that occurred.
     * Examples: TRANSACTION_CREATED, PAYMENT_RECEIVED, OCR_COMPLETED, POLICY_APPROVED
     */
    private String eventType;

    /**
     * Source module that generated this event.
     * Examples: AUTH, TRANSACTION, OCR, AI, POLICY_ENGINE, WHATSAPP
     */
    private String module;

    /**
     * UUID of the actor (user, system, webhook) that initiated this event.
     */
    private UUID actorId;

    /**
     * Type of actor that initiated this event.
     * Examples: USER, SYSTEM, WEBHOOK, AI, POLICY_ENGINE
     */
    private String actorType;

    /**
     * Correlation ID for tracing this request across all modules.
     * Enables end-to-end visibility across the entire transaction workflow.
     */
    private String correlationId;

    /**
     * Previous state before this event (for state transitions).
     * Example: "CREATED", "PAYMENT_RECEIVED", "EVIDENCE_PENDING"
     */
    private String previousState;

    /**
     * New state after this event (for state transitions).
     * Example: "PAYMENT_RECEIVED", "EVIDENCE_PENDING", "POLICY_APPROVED"
     */
    private String newState;

    /**
     * Event-specific data as JSON.
     * Examples:
     * - OCR: extraction results, confidence scores
     * - AI: regulatory risk assessment, reasoning
     * - Policy: rule evaluation results, decision justification
     * - Webhook: headers, signature verification status
     * - WhatsApp: message ID, template name, delivery status
     */
    private JsonNode eventData;

    /**
     * Immutable timestamp when this event was recorded.
     */
    private Instant createdAt;
}
