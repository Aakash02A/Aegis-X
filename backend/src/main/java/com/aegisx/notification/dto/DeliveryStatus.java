package com.aegisx.notification.dto;

/**
 * DeliveryStatus represents the lifecycle state of a notification.
 * 
 * SENT: Message successfully submitted to Meta WhatsApp API
 * DELIVERED: Meta confirmed message reached recipient's device
 * READ: Meta confirmed message was read by recipient
 * FAILED: Delivery failed (invalid phone, expired media, etc.)
 * 
 * Note: Status updates flow only forward; never overwritten.
 */
public enum DeliveryStatus {
    SENT,
    DELIVERED,
    READ,
    FAILED
}
