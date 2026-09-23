package com.aegisx.notification.dto;

/**
 * Direction represents the flow of a notification message.
 * 
 * OUTBOUND: Message sent from Aegis-X to user (e.g., document request)
 * INBOUND: Message received from user (e.g., uploaded evidence via WhatsApp)
 */
public enum Direction {
    OUTBOUND,
    INBOUND
}
