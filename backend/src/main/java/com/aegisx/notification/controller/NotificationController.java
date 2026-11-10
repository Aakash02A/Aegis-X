package com.aegisx.notification.controller;

import com.aegisx.notification.dto.NotificationResponse;
import com.aegisx.auth.entity.Role;
import com.aegisx.document.service.DocumentService;
import com.aegisx.notification.entity.NotificationMessage;
import com.aegisx.notification.repository.NotificationRepository;
import com.aegisx.notification.service.NotificationService;
import com.aegisx.transaction.service.TransactionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * NotificationController provides APIs for notification history.
 * 
 * Endpoints:
 * - GET /api/transactions/{id}/notifications: Get communication history
 * 
 * Purpose:
 * - Audit trail display
 * - Compliance reporting
 * - Merchant visibility into communication
 */
@Slf4j
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class NotificationController {
    
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final TransactionService transactionService;
    private final DocumentService documentService;

    public record DocumentRequest(@NotBlank @Pattern(regexp = "^[1-9][0-9]{7,14}$") String recipientPhone) {}

    @PostMapping("/{id}/notifications/document-request")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<NotificationResponse> requestMissingDocuments(
            @PathVariable("id") UUID transactionId,
            @Valid @RequestBody DocumentRequest request,
            Authentication authentication) {
        transactionService.getTransaction(transactionId, authentication.getName(), Role.MERCHANT);
        var transaction = transactionService.getTransactionById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
        documentService.initializeChecklist(transactionId);
        NotificationMessage message = notificationService.requestMissingDocuments(transaction, request.recipientPhone());
        if (message == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(toResponse(message));
    }
    
    /**
     * Get notification history for a transaction.
     * 
     * Returns all inbound and outbound messages, ordered by creation time (newest first).
     * 
     * GET /api/transactions/{id}/notifications
     * 
     * @param transactionId Transaction to query
     * @return List of notifications
     */
    @GetMapping("/{id}/notifications")
    @PreAuthorize("hasAnyRole('MERCHANT', 'COMPLIANCE_OFFICER', 'ADMIN')")
    public ResponseEntity<?> getNotificationHistory(@PathVariable("id") UUID transactionId, Authentication authentication) {
        try {
            log.info("Retrieving notification history: transactionId={}", transactionId);
            transactionService.getAuthorizedTransaction(transactionId, authentication);
            
            // Query all notifications for transaction
            List<NotificationMessage> messages = notificationRepository
                .findByTransactionIdOrderByCreatedAtDesc(transactionId);
            
            // Convert to response DTOs
            List<NotificationResponse> responses = messages.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
            
            log.info("Retrieved notification history: transactionId={}, count={}", transactionId, responses.size());
            
            return ResponseEntity.ok(responses);
            
        } catch (com.aegisx.common.exception.TransactionNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error retrieving notification history: transactionId={}, error={}", 
                transactionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error retrieving notification history");
        }
    }
    
    /**
     * Get outbound messages (document requests) for a transaction.
     * 
     * GET /api/transactions/{id}/notifications?direction=OUTBOUND
     * 
     * @param transactionId Transaction to query
     * @return List of outbound notifications
     */
    @GetMapping("/{id}/notifications/outbound")
    @PreAuthorize("hasAnyRole('MERCHANT', 'COMPLIANCE_OFFICER', 'ADMIN')")
    public ResponseEntity<?> getOutboundNotifications(@PathVariable("id") UUID transactionId, Authentication authentication) {
        try {
            log.info("Retrieving outbound notifications: transactionId={}", transactionId);
            transactionService.getAuthorizedTransaction(transactionId, authentication);
            
            List<NotificationMessage> messages = notificationRepository
                .findByTransactionIdOrderByCreatedAtDesc(transactionId);
            
            List<NotificationResponse> responses = messages.stream()
                .filter(m -> m.getDirection().toString().equals("OUTBOUND"))
                .map(this::toResponse)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(responses);
            
        } catch (com.aegisx.common.exception.TransactionNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error retrieving outbound notifications: transactionId={}, error={}", 
                transactionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error retrieving outbound notifications");
        }
    }
    
    /**
     * Get inbound messages for a transaction.
     * 
     * GET /api/transactions/{id}/notifications/inbound
     * 
     * @param transactionId Transaction to query
     * @return List of inbound notifications
     */
    @GetMapping("/{id}/notifications/inbound")
    @PreAuthorize("hasAnyRole('MERCHANT', 'COMPLIANCE_OFFICER', 'ADMIN')")
    public ResponseEntity<?> getInboundNotifications(@PathVariable("id") UUID transactionId, Authentication authentication) {
        try {
            log.info("Retrieving inbound notifications: transactionId={}", transactionId);
            transactionService.getAuthorizedTransaction(transactionId, authentication);
            
            List<NotificationMessage> messages = notificationRepository
                .findByTransactionIdOrderByCreatedAtDesc(transactionId);
            
            List<NotificationResponse> responses = messages.stream()
                .filter(m -> m.getDirection().toString().equals("INBOUND"))
                .map(this::toResponse)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(responses);
        } catch (com.aegisx.common.exception.TransactionNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error retrieving inbound notifications: transactionId={}, error={}", 
                transactionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error retrieving inbound notifications");
        }
    }
    
    private NotificationResponse toResponse(NotificationMessage msg) {
        return NotificationResponse.builder()
            .id(msg.getId())
            .transactionId(msg.getTransactionId())
            .channel(msg.getChannel())
            .direction(msg.getDirection())
            .recipient(msg.getRecipient())
            .templateName(msg.getTemplateName())
            .messageId(msg.getMessageId())
            .status(msg.getStatus())
            .deliveryTimestamp(msg.getDeliveryTimestamp())
            .readTimestamp(msg.getReadTimestamp())
            .failureReason(msg.getFailureReason())
            .createdAt(msg.getCreatedAt())
            .updatedAt(msg.getUpdatedAt())
            .build();
    }
}
