package com.aegisx.notification.service;

import com.aegisx.common.exception.InvalidDocumentException;
import com.aegisx.document.entity.DocumentType;
import com.aegisx.document.service.DocumentService;
import com.aegisx.integration.whatsapp.WhatsAppMediaService;
import com.aegisx.integration.whatsapp.WhatsAppWebhookService;
import com.aegisx.notification.dto.DeliveryStatus;
import com.aegisx.notification.dto.WhatsAppWebhookPayload;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import org.springframework.web.multipart.MultipartFile;

/**
 * InboundMessageService processes inbound WhatsApp events.
 * 
 * Responsibilities:
 * - Route inbound messages to document upload
 * - Process delivery/read receipts
 * - Map phone numbers to transactions
 * - Download and ingest media
 * 
 * Design:
 * - All messages must reference or map to existing transaction
 * - Media downloads immediately (expires in 5 minutes)
 * - Documents flow through DocumentService (never bypassed)
 * - Idempotency via message ID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundMessageService {
    
    private final WhatsAppWebhookService webhookService;
    private final WhatsAppMediaService mediaService;
    private final DocumentService documentService;
    private final NotificationService notificationService;
    private final TransactionRepository transactionRepository;
    private final TransactionMappingService transactionMappingService;
    
    /**
     * Process inbound message from WhatsApp.
     * 
     * @param payload Webhook payload
     */
    @Transactional
    public void processInboundMessage(WhatsAppWebhookPayload payload) {
        log.info("Processing inbound message");
        
        try {
            String senderPhone = webhookService.extractSenderPhone(payload);
            String messageId = webhookService.extractMessageId(payload);
            
            log.debug("Inbound message: from={}, messageId={}", senderPhone, messageId);
            
            // Find transaction for this sender
            UUID transactionId = transactionMappingService.findTransactionForPhone(senderPhone);
            
            if (transactionId == null) {
                log.warn("No transaction found for inbound message: from={}, messageId={}", senderPhone, messageId);
                return;
            }
            
            // Record inbound notification
            notificationService.recordInboundNotification(transactionId, senderPhone, messageId);
            
            // Extract message
            WhatsAppWebhookPayload.Message message = payload.getEntries().get(0)
                .getChanges().get(0).getValue().getMessages().get(0);
            
            // Process based on message type
            if ("text".equals(message.getType())) {
                processTextMessage(transactionId, message.getText().getBody());
            } else if ("document".equals(message.getType())) {
                processDocumentMessage(transactionId, message.getDocument());
            } else if ("image".equals(message.getType())) {
                processImageMessage(transactionId, message.getImage());
            } else if ("pdf".equals(message.getType())) {
                processDocumentMessage(transactionId, message.getDocument());
            } else {
                log.debug("Unsupported message type: type={}", message.getType());
            }
            
        } catch (Exception e) {
            log.error("Error processing inbound message: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Process text message (e.g., transaction reference).
     * 
     * @param transactionId Transaction for this message
     * @param text Message text
     */
    private void processTextMessage(UUID transactionId, String text) {
        log.debug("Processing text message: transactionId={}, text={}", transactionId, text);
        
        // Could implement transaction reference extraction here
        // For MVP, just log it
    }
    
    /**
     * Process document message (invoice, shipping proof, export declaration).
     * 
     * @param transactionId Transaction for this message
     * @param document Document attachment
     */
    private void processDocumentMessage(UUID transactionId, WhatsAppWebhookPayload.Document document) {
        log.info("Processing document message: transactionId={}, filename={}, mediaId={}", 
            transactionId, document.getFilename(), document.getId());
        
        try {
            // Download media from Meta API
            String mediaUrl = mediaService.getMediaUrl(document.getId());
            if (mediaUrl == null) {
                log.error("Failed to get media URL: mediaId={}", document.getId());
                return;
            }
            
            byte[] fileBytes = mediaService.downloadMedia(mediaUrl);
            if (fileBytes == null) {
                log.error("Failed to download media: mediaId={}", document.getId());
                return;
            }
            
            // Determine document type from filename
            DocumentType docType = inferDocumentType(document.getFilename());
            
            // Upload through DocumentService
            uploadInboundDocument(transactionId, docType, document.getFilename(), fileBytes, document.getMime_type());
            
            log.info("Document ingested successfully: transactionId={}, type={}, filename={}", 
                transactionId, docType, document.getFilename());
            
        } catch (InvalidDocumentException e) {
            log.warn("Invalid document: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Error processing document message: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Process image message (invoice scan, etc.).
     * 
     * @param transactionId Transaction for this message
     * @param image Image attachment
     */
    private void processImageMessage(UUID transactionId, WhatsAppWebhookPayload.Image image) {
        log.info("Processing image message: transactionId={}, mediaId={}", transactionId, image.getId());
        
        try {
            // Download media from Meta API
            String mediaUrl = mediaService.getMediaUrl(image.getId());
            if (mediaUrl == null) {
                log.error("Failed to get media URL: mediaId={}", image.getId());
                return;
            }
            
            byte[] fileBytes = mediaService.downloadMedia(mediaUrl);
            if (fileBytes == null) {
                log.error("Failed to download media: mediaId={}", image.getId());
                return;
            }
            
            // Upload as invoice image
            uploadInboundDocument(transactionId, DocumentType.INVOICE,
                    "invoice_" + System.currentTimeMillis() + ".jpg", fileBytes, image.getMime_type());
            
            log.info("Image ingested successfully: transactionId={}", transactionId);
            
        } catch (InvalidDocumentException e) {
            log.warn("Invalid image: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Error processing image message: {}", e.getMessage(), e);
        }
    }

    private void uploadInboundDocument(UUID transactionId, DocumentType type, String filename,
                                       byte[] bytes, String contentType) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
        MultipartFile file = new MultipartFile() {
            @Override public String getName() { return "file"; }
            @Override public String getOriginalFilename() { return filename; }
            @Override public String getContentType() { return contentType; }
            @Override public boolean isEmpty() { return bytes.length == 0; }
            @Override public long getSize() { return bytes.length; }
            @Override public byte[] getBytes() { return bytes.clone(); }
            @Override public InputStream getInputStream() { return new java.io.ByteArrayInputStream(bytes); }
            @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), bytes); }
        };
        documentService.uploadDocument(transactionId, file, type, transaction.getMerchant().getEmail());
    }
    
    /**
     * Process delivery receipt (message delivered, read).
     * 
     * @param payload Webhook payload
     */
    @Transactional
    public void processDeliveryReceipt(WhatsAppWebhookPayload payload) {
        log.debug("Processing delivery receipt");
        
        try {
            String messageId = webhookService.extractMessageId(payload);
            
            if (messageId == null) {
                log.debug("No message ID in delivery receipt");
                return;
            }
            
            // Extract status
            WhatsAppWebhookPayload.Status status = payload.getEntries().get(0)
                .getChanges().get(0).getValue().getStatuses().get(0);
            
            DeliveryStatus deliveryStatus = switch (status.getStatus()) {
                case "sent" -> DeliveryStatus.SENT;
                case "delivered" -> DeliveryStatus.DELIVERED;
                case "read" -> DeliveryStatus.READ;
                case "failed" -> DeliveryStatus.FAILED;
                default -> null;
            };
            
            if (deliveryStatus != null) {
                notificationService.updateDeliveryStatus(messageId, deliveryStatus);
            }
            
        } catch (Exception e) {
            log.error("Error processing delivery receipt: {}", e.getMessage(), e);
        }
    }
    
    private DocumentType inferDocumentType(String filename) {
        if (filename == null) {
            return DocumentType.INVOICE;
        }
        
        String lower = filename.toLowerCase();
        
        if (lower.contains("invoice")) {
            return DocumentType.INVOICE;
        } else if (lower.contains("shipping") || lower.contains("waybill")) {
            return DocumentType.SHIPPING_PROOF;
        } else if (lower.contains("export")) {
            return DocumentType.EXPORT_DECLARATION;
        }
        
        return DocumentType.INVOICE;  // Default
    }
}
