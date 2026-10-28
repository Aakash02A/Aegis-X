package com.aegisx.audit.entity;

import com.aegisx.transaction.entity.Transaction;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit event entity representing a significant business event.
 * 
 * This entity records every important state change or action across all modules:
 * authentication, transactions, payments, documents, OCR, AI analysis, policy decisions,
 * and WhatsApp notifications.
 * 
 * Audit events are:
 * - Append-only (no updates or deletes allowed)
 * - Immutable (created_at never changes)
 * - Correlated (all events in a request flow share a correlation_id)
 * - Traceable (links to transaction, actor, and module)
 * - Comprehensive (stores event data as JSONB for extensibility)
 * 
 * This enables:
 * - Full audit trail for compliance
 * - Root cause analysis of failures
 * - Before/after state comparison
 * - Request traceability across module boundaries
 * - Regulatory explainability of decisions
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "audit_events",
    indexes = {
        @Index(name = "idx_audit_transaction_id", columnList = "transaction_id"),
        @Index(name = "idx_audit_correlation_id", columnList = "correlation_id"),
        @Index(name = "idx_audit_event_type", columnList = "event_type"),
        @Index(name = "idx_audit_created_at", columnList = "created_at"),
        @Index(name = "idx_audit_transaction_created", columnList = "transaction_id, created_at"),
        @Index(name = "idx_audit_module_created", columnList = "module, created_at"),
        @Index(name = "idx_audit_actor_type_created", columnList = "actor_type, created_at")
    }
)
public class AuditEvent {

    /**
     * Unique identifier for this audit event.
     * Generated as UUID, immutable.
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Foreign key to the transaction this event belongs to.
     * Every audit event must be linked to a transaction for traceability.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "transaction_id",
        nullable = false,
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_audit_transaction")
    )
    private Transaction transaction;

    /**
     * Type of business event that occurred.
     * Examples: TRANSACTION_CREATED, PAYMENT_RECEIVED, OCR_COMPLETED, POLICY_APPROVED
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private AuditEventType eventType;

    /**
     * Source module that generated this event.
     * Examples: AUTH, TRANSACTION, OCR, AI, POLICY_ENGINE, WHATSAPP
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "module", nullable = false, updatable = false, length = 50)
    private AuditModule module;

    /**
     * UUID of the actor (user, system, webhook) that initiated this event.
     * Can be null for pure system-initiated events.
     */
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    /**
     * Type of actor that initiated this event.
     * Examples: USER, SYSTEM, WEBHOOK, AI, POLICY_ENGINE
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false, length = 50)
    private ActorType actorType;

    /**
     * Correlation ID for tracing this request across all modules.
     * Generated per HTTP request, propagated through transaction lifecycle.
     * Enables end-to-end visibility: Razorpay → Transaction → OCR → AI → Policy → WhatsApp
     */
    @Column(name = "correlation_id", nullable = false, updatable = false, length = 255)
    private String correlationId;

    /**
     * Previous state before this event (for state transitions).
     * Null if this is an initial state or a non-state-change event.
     * Example: "CREATED", "PAYMENT_RECEIVED", "EVIDENCE_PENDING"
     */
    @Column(name = "previous_state", updatable = false, length = 100)
    private String previousState;

    /**
     * New state after this event (for state transitions).
     * Null if this is a non-state-change event.
     * Example: "PAYMENT_RECEIVED", "EVIDENCE_PENDING", "POLICY_APPROVED"
     */
    @Column(name = "new_state", updatable = false, length = 100)
    private String newState;

    /**
     * Event-specific data as JSONB.
     * Stores context-specific information:
     * - OCR: extraction results, confidence scores
     * - AI: regulatory risk assessment, reasoning
     * - Policy: rule evaluation results, decision justification
     * - Webhook: headers, signature verification status
     * - WhatsApp: message ID, template name, delivery status
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_data", updatable = false)
    private JsonNode eventData;

    /**
     * Immutable timestamp when this event was recorded.
     * Used for chronological ordering and audit trail validation.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Lifecycle hook to ensure immutability and generate defaults.
     */
    @jakarta.persistence.PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
