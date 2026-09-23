package com.aegisx.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * NotificationResponse represents a notification message in API responses.
 * 
 * Used for:
 * - GET /api/transactions/{id}/notifications
 * - Audit trail display
 * - Communication history
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationResponse {
    
    private UUID id;
    
    private UUID transactionId;
    
    private String channel;
    
    private Direction direction;
    
    private String recipient;
    
    private String templateName;
    
    private String messageId;
    
    private DeliveryStatus status;
    
    private Instant deliveryTimestamp;
    
    private Instant readTimestamp;
    
    private String failureReason;
    
    private Instant createdAt;
    
    private Instant updatedAt;
}
