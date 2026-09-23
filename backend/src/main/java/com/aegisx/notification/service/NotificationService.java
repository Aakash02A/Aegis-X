package com.aegisx.notification.service;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.document.entity.RequiredDocument;
import com.aegisx.document.repository.RequiredDocumentRepository;
import com.aegisx.integration.whatsapp.WhatsAppClient;
import com.aegisx.notification.dto.DeliveryStatus;
import com.aegisx.notification.dto.Direction;
import com.aegisx.notification.entity.NotificationMessage;
import com.aegisx.notification.repository.NotificationRepository;
import com.aegisx.transaction.entity.Transaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * NotificationService orchestrates outbound document requests.
 * 
 * Responsibilities:
 * - Request missing documents from users
 * - Build WhatsApp template parameters
 * - Track message IDs for idempotency
 * - Store notification records for audit trail
 * 
 * Design:
 * - Triggered when transaction enters EVIDENCE_PENDING state
 * - Always checks if WhatsApp is configured before sending
 * - Idempotent: duplicate requests use same message
 * - Full audit trail stored regardless of success
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    
    private final WhatsAppClient whatsAppClient;
    private final NotificationRepository notificationRepository;
    private final RequiredDocumentRepository requiredDocumentRepository;
    private final ObjectMapper objectMapper;
    
    /**
     * Request missing documents from user via WhatsApp.
     * 
     * Called when transaction enters EVIDENCE_PENDING state.
     * 
     * @param transaction Transaction requiring documents
     * @param recipientPhone User phone number (international format, no +)
     * @return Notification record
     */
    @Transactional
    public NotificationMessage requestMissingDocuments(Transaction transaction, String recipientPhone) {
        log.info("Requesting missing documents for transaction: id={}, phone={}", transaction.getId(), recipientPhone);
        
        try {
            // Get required documents for this transaction
            List<RequiredDocument> required = requiredDocumentRepository.findByTransactionId(transaction.getId());
            List<String> pendingDocuments = required.stream()
                .filter(d -> !d.isFulfilled())
                .map(d -> d.getDocumentType().getDisplayName())
                .collect(Collectors.toList());
            
            if (pendingDocuments.isEmpty()) {
                log.debug("No missing documents for transaction: id={}", transaction.getId());
                return null;
            }
            
            // Build template parameters
            JsonNode templateParams = buildDocumentRequestParams(
                transaction.getReferenceNumber(),
                pendingDocuments
            );
            
            // Send WhatsApp template message
            String messageId = null;
            if (whatsAppClient.isConfigured()) {
                messageId = whatsAppClient.sendTemplateMessage(
                    recipientPhone,
                    "document_request",
                    templateParams
                );
            }
            
            // Store notification record (always, regardless of send success)
            NotificationMessage notification = NotificationMessage.builder()
                .transactionId(transaction.getId())
                .channel("WHATSAPP")
                .direction(Direction.OUTBOUND)
                .recipient(recipientPhone)
                .templateName("document_request")
                .messageId(messageId)
                .status(messageId != null ? DeliveryStatus.SENT : DeliveryStatus.FAILED)
                .failureReason(messageId == null ? "WhatsApp not configured or send failed" : null)
                .updatedAt(Instant.now())
                .build();
            
            NotificationMessage saved = notificationRepository.save(notification);
            
            log.info("Document request notification created: id={}, messageId={}, status={}", 
                saved.getId(), messageId, saved.getStatus());
            
            return saved;
            
        } catch (Exception e) {
            log.error("Error requesting missing documents for transaction: id={}, error={}", 
                transaction.getId(), e.getMessage(), e);
            
            // Still persist failure record
            NotificationMessage notification = NotificationMessage.builder()
                .transactionId(transaction.getId())
                .channel("WHATSAPP")
                .direction(Direction.OUTBOUND)
                .recipient(recipientPhone)
                .templateName("document_request")
                .status(DeliveryStatus.FAILED)
                .failureReason("Exception: " + e.getMessage())
                .updatedAt(Instant.now())
                .build();
            
            return notificationRepository.save(notification);
        }
    }
    
    /**
     * Build WhatsApp template parameters for document request.
     * 
     * @param transactionRef Transaction reference number
     * @param documentNames List of required document names
     * @return JSON parameters for WhatsApp template
     */
    private JsonNode buildDocumentRequestParams(String transactionRef, List<String> documentNames) {
        ObjectNode params = objectMapper.createObjectNode();
        
        ArrayNode body = objectMapper.createArrayNode();
        ObjectNode param1 = objectMapper.createObjectNode();
        param1.put("type", "text");
        param1.put("text", transactionRef);
        body.add(param1);
        
        ObjectNode param2 = objectMapper.createObjectNode();
        param2.put("type", "text");
        param2.put("text", String.join(", ", documentNames));
        body.add(param2);
        
        params.set("body", body);
        
        return params;
    }
    
    /**
     * Record inbound notification (message or media received).
     * 
     * @param transactionId Transaction this message belongs to
     * @param senderPhone Sender phone number
     * @param messageId Meta message ID
     * @return Notification record
     */
    @Transactional
    public NotificationMessage recordInboundNotification(UUID transactionId, String senderPhone, String messageId) {
        log.info("Recording inbound notification: transactionId={}, phone={}, messageId={}", 
            transactionId, senderPhone, messageId);
        
        // Check if this message already recorded (idempotency)
        if (messageId != null) {
            var existing = notificationRepository.findByMessageId(messageId);
            if (existing.isPresent()) {
                log.debug("Inbound message already recorded: messageId={}", messageId);
                return existing.get();
            }
        }
        
        NotificationMessage notification = NotificationMessage.builder()
            .transactionId(transactionId)
            .channel("WHATSAPP")
            .direction(Direction.INBOUND)
            .recipient(senderPhone)
            .messageId(messageId)
            .status(DeliveryStatus.DELIVERED)
            .updatedAt(Instant.now())
            .build();
        
        NotificationMessage saved = notificationRepository.save(notification);
        
        log.info("Inbound notification recorded: id={}, messageId={}", saved.getId(), messageId);
        
        return saved;
    }
    
    /**
     * Update delivery status for a notification.
     * 
     * @param messageId Meta message ID
     * @param newStatus New delivery status
     */
    @Transactional
    public void updateDeliveryStatus(String messageId, DeliveryStatus newStatus) {
        if (messageId == null) {
            log.debug("Cannot update delivery status: messageId is null");
            return;
        }
        
        var notification = notificationRepository.findByMessageId(messageId);
        
        if (notification.isEmpty()) {
            log.warn("Notification not found for status update: messageId={}", messageId);
            return;
        }
        
        NotificationMessage msg = notification.get();
        DeliveryStatus oldStatus = msg.getStatus();
        
        // Only update if new status is "higher" than current
        if (canTransitionStatus(oldStatus, newStatus)) {
            msg.setStatus(newStatus);
            msg.setUpdatedAt(Instant.now());
            
            if (newStatus == DeliveryStatus.DELIVERED && msg.getDeliveryTimestamp() == null) {
                msg.setDeliveryTimestamp(Instant.now());
            }
            if (newStatus == DeliveryStatus.READ && msg.getReadTimestamp() == null) {
                msg.setReadTimestamp(Instant.now());
            }
            
            notificationRepository.save(msg);
            
            log.info("Notification status updated: messageId={}, status: {} -> {}", 
                messageId, oldStatus, newStatus);
        } else {
            log.debug("Ignoring status transition: messageId={}, {} -> {}", messageId, oldStatus, newStatus);
        }
    }
    
    private boolean canTransitionStatus(DeliveryStatus current, DeliveryStatus next) {
        if (current == next) return false;
        if (current == DeliveryStatus.FAILED) return false;  // Failed is terminal
        
        int currentPriority = getPriority(current);
        int nextPriority = getPriority(next);
        
        return nextPriority > currentPriority;
    }
    
    private int getPriority(DeliveryStatus status) {
        return switch (status) {
            case SENT -> 1;
            case DELIVERED -> 2;
            case READ -> 3;
            case FAILED -> 0;
        };
    }
}
