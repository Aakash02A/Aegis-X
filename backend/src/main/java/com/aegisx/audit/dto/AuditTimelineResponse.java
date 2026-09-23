package com.aegisx.audit.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * Response DTO for the complete audit timeline of a transaction.
 * Represents the chronological history of all business events for a single transaction.
 * 
 * This is the primary response for the GET /api/transactions/{id}/audit endpoint.
 * It allows compliance officers, auditors, and merchants to see the complete,
 * immutable history of decisions and state changes for a transaction.
 * 
 * Examples of timeline flow:
 * 1. Transaction Created (TRANSACTION_CREATED)
 * 2. Razorpay Webhook Received (WEBHOOK_RECEIVED)
 * 3. Payment Received (PAYMENT_RECEIVED)
 * 4. Document Requested via WhatsApp (WHATSAPP_MESSAGE_SENT)
 * 5. Document Uploaded (DOCUMENT_UPLOADED)
 * 6. OCR Processing Started (OCR_STARTED)
 * 7. OCR Completed (OCR_COMPLETED)
 * 8. AI Analysis Started (AI_ANALYSIS_STARTED)
 * 9. AI Analysis Completed (AI_ANALYSIS_COMPLETED)
 * 10. Policy Evaluation Started (POLICY_EVALUATION_STARTED)
 * 11. Policy Approved (POLICY_APPROVED)
 * 12. Transaction Completed (TRANSACTION_COMPLETED)
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditTimelineResponse {

    /**
     * ID of the transaction whose audit timeline is being returned.
     */
    private UUID transactionId;

    /**
     * Reference code of the transaction (e.g., "TXN-20260922-001").
     * Makes it easier for users to identify the transaction in logs and reports.
     */
    private String transactionReference;

    /**
     * ID of the merchant who owns this transaction.
     * Used for RBAC: merchants can only view their own transactions.
     */
    private UUID merchantId;

    /**
     * Current state of the transaction.
     * Examples: CREATED, PAYMENT_RECEIVED, EVIDENCE_PENDING, OCR_IN_PROGRESS, POLICY_APPROVED, COMPLETED
     */
    private String currentState;

    /**
     * Chronological list of all audit events for this transaction, ordered by created_at ASC.
     * Each event represents a significant state change or action that occurred during the
     * transaction's lifecycle. The timeline is immutable and append-only.
     */
    private List<AuditEventResponse> events;

    /**
     * Total number of events in this timeline.
     * Useful for UI pagination and progress indication.
     */
    private int totalEvents;

    /**
     * Human-readable summary of the timeline.
     * Examples:
     * - "3 events: Created → Payment Received → Evidence Pending"
     * - "12 events: Complete workflow from creation to approval"
     * Useful for compliance officer dashboards and audit reports.
     */
    private String summary;
}
