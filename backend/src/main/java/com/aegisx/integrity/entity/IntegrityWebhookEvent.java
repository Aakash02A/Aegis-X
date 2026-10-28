package com.aegisx.integrity.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.integrity.domain.WebhookProcessingState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Module C: durable webhook event record.
 *
 * <p>The unique constraint on {@code (workspace_id, provider, provider_event_id)} is the duplicate
 * guard. It is enforced by the database, not by an in-memory set, so it survives restarts and
 * concurrent deliveries — a repeated event cannot create a second business effect. The workspace is
 * part of the key because provider event ids are allocated per provider <em>account</em>: scoping
 * the guard to the provider alone made one workspace's delivery look like a duplicate of another's,
 * and the losing workspace silently never saw the event.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "pi_webhook_events",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_pi_webhook_events_workspace_provider_event",
                        columnNames = {"workspace_id", "provider", "provider_event_id"})
        })
public class IntegrityWebhookEvent extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    /** Provider event id (Razorpay {@code x-razorpay-event-id}). Synthesised from a hash when absent. */
    @Column(name = "provider_event_id", nullable = false, length = 120)
    private String providerEventId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "signature_verified", nullable = false)
    private boolean signatureVerified;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_state", nullable = false, length = 30)
    private WebhookProcessingState processingState;

    /** Set when this delivery repeated an already-seen provider event id. */
    @Column(name = "duplicate_of")
    private UUID duplicateOf;

    @Column(name = "provider_resource_id", length = 100)
    private String providerResourceId;

    @Column(name = "provider_created_at")
    private Instant providerCreatedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    /** Minimum raw payload retained for debugging/audit. Subject to the retention policy. */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Builder.Default
    @Column(name = "payload_purged", nullable = false)
    private boolean payloadPurged = false;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Builder.Default
    @Column(name = "simulated", nullable = false)
    private boolean simulated = false;
}
