package com.aegisx.integrity.entity;

import com.aegisx.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One received webhook <em>delivery</em>, as distinct from one unique provider <em>event</em>.
 *
 * <p>The provider delivers at least once, so the same event id legitimately arrives several times.
 * The unique guard lives on {@link IntegrityWebhookEvent}; this table is the append-only log of
 * every attempt, which is what lets the engine report "this event was delivered twice" as a
 * fact rather than an assumption.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pi_webhook_deliveries")
public class WebhookDelivery extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_event_id", nullable = false, length = 120)
    private String providerEventId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    /** The deduplicated event row this delivery maps to. */
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    /** True for the first delivery of this event id. */
    @Column(name = "first_delivery", nullable = false)
    private boolean firstDelivery;

    @Column(name = "signature_verified", nullable = false)
    private boolean signatureVerified;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "provider_created_at")
    private Instant providerCreatedAt;

    @Column(name = "body_sha256", length = 64)
    private String bodySha256;

    @Builder.Default
    @Column(name = "simulated", nullable = false)
    private boolean simulated = false;
}
