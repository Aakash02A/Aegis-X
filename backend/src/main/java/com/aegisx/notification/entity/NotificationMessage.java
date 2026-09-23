package com.aegisx.notification.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.notification.dto.Direction;
import com.aegisx.notification.dto.DeliveryStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * NotificationMessage represents a single communication event (inbound or outbound).
 * 
 * Purpose:
 * - Immutable record of all WhatsApp and external integration communication
 * - Compliance audit trail for communication flow
 * - Idempotency tracking for webhook events
 * 
 * Design:
 * - Direction: INBOUND (webhook received) or OUTBOUND (message sent to user)
 * - Status: SENT, DELIVERED, READ, FAILED (updated via webhooks, never overwritten)
 * - Message ID: Meta WhatsApp message ID for idempotency
 * - Template Name: WhatsApp template identifier for outbound messages
 * 
 * Timestamps:
 * - created_at: When record was inserted
 * - delivery_timestamp: When Meta confirmed delivery (from webhook)
 * - read_timestamp: When message was read (from webhook)
 * - updated_at: Last status update
 */
@Entity
@Table(name = "notification_messages", indexes = {
    @Index(name = "idx_notification_transaction_id", columnList = "transaction_id"),
    @Index(name = "idx_notification_message_id", columnList = "message_id"),
    @Index(name = "idx_notification_recipient", columnList = "recipient"),
    @Index(name = "idx_notification_status_created", columnList = "status, created_at"),
    @Index(name = "idx_notification_transaction_direction", columnList = "transaction_id, direction")
})
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationMessage extends BaseEntity {
    
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;
    
    @Column(name = "channel", nullable = false, length = 50)
    private String channel;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 20)
    private Direction direction;
    
    @Column(name = "recipient", nullable = false, length = 255)
    private String recipient;
    
    @Column(name = "template_name", length = 255)
    private String templateName;
    
    @Column(name = "message_id", length = 255)
    private String messageId;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private DeliveryStatus status;
    
    @Column(name = "delivery_timestamp")
    private Instant deliveryTimestamp;
    
    @Column(name = "read_timestamp")
    private Instant readTimestamp;
    
    @Column(name = "failure_reason", length = 500)
    private String failureReason;
    
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
