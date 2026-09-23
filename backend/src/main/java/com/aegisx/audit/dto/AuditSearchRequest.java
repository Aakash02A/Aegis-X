package com.aegisx.audit.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Request DTO for audit event search.
 * Used to filter audit events by various criteria.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditSearchRequest {

    /**
     * Filter by transaction ID (specific transaction's audit trail)
     */
    private UUID transactionId;

    /**
     * Filter by event type (e.g., POLICY_APPROVED, OCR_COMPLETED)
     */
    private String eventType;

    /**
     * Filter by source module (e.g., POLICY_ENGINE, OCR, AI)
     */
    private String module;

    /**
     * Filter by actor ID (e.g., user who uploaded document)
     */
    private UUID actorId;

    /**
     * Filter by actor type (e.g., USER, SYSTEM, WEBHOOK, AI, POLICY_ENGINE)
     */
    private String actorType;

    /**
     * Filter by start date (inclusive)
     */
    private Instant startDate;

    /**
     * Filter by end date (inclusive)
     */
    private Instant endDate;

    /**
     * Search query (optional, for full-text search on event_data)
     */
    private String query;
}
