package com.aegisx.evidence.service;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.notification.service.NotificationService;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for sending evidence-related notifications.
 * Integrates with WhatsApp notification service to inform merchants about missing documents.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceNotificationService {

    private final NotificationService notificationService;
    private final EvidenceChecklistService evidenceChecklistService;
    private final TransactionService transactionService;

    /**
     * Sends notification about missing documents to the merchant.
     */
    public void sendMissingDocumentsNotification(UUID transactionId) {
        log.info("Sending missing documents notification for transaction: {}", transactionId);
        
        try {
            Transaction transaction = transactionService.getTransactionById(transactionId)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
            
            List<DocumentType> missingDocuments = evidenceChecklistService.getMissingDocuments(transactionId);
            
            if (missingDocuments.isEmpty()) {
                log.debug("No missing documents for transaction: {}, skipping notification", transactionId);
                return;
            }
            
            String message = buildMissingDocumentsMessage(transaction, missingDocuments);
            
            // Send WhatsApp notification (assuming phone number is available from merchant profile)
            // For MVP, we'll use a placeholder phone number or merchant contact info
            String merchantPhone = getMerchantPhoneNumber(transaction);
            
            if (merchantPhone != null) {
                notificationService.sendWhatsAppMessage(merchantPhone, message);
                log.info("Missing documents notification sent to merchant: {} for transaction: {}", 
                        merchantPhone, transactionId);
            } else {
                log.warn("No phone number available for merchant, cannot send WhatsApp notification for transaction: {}", 
                        transactionId);
            }
            
        } catch (Exception e) {
            log.error("Failed to send missing documents notification for transaction: {}", transactionId, e);
        }
    }

    /**
     * Sends evidence completion notification to the merchant.
     */
    public void sendEvidenceCompleteNotification(UUID transactionId) {
        log.info("Sending evidence complete notification for transaction: {}", transactionId);
        
        try {
            Transaction transaction = transactionService.getTransactionById(transactionId)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
            
            String message = buildEvidenceCompleteMessage(transaction);
            String merchantPhone = getMerchantPhoneNumber(transaction);
            
            if (merchantPhone != null) {
                notificationService.sendWhatsAppMessage(merchantPhone, message);
                log.info("Evidence complete notification sent to merchant: {} for transaction: {}", 
                        merchantPhone, transactionId);
            }
            
        } catch (Exception e) {
            log.error("Failed to send evidence complete notification for transaction: {}", transactionId, e);
        }
    }

    /**
     * Sends notification when document requirements change.
     */
    public void sendRequirementChangeNotification(UUID transactionId, String reason) {
        log.info("Sending requirement change notification for transaction: {}", transactionId);
        
        try {
            Transaction transaction = transactionService.getTransactionById(transactionId)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
            
            List<DocumentType> missingDocuments = evidenceChecklistService.getMissingDocuments(transactionId);
            String message = buildRequirementChangeMessage(transaction, missingDocuments, reason);
            String merchantPhone = getMerchantPhoneNumber(transaction);
            
            if (merchantPhone != null) {
                notificationService.sendWhatsAppMessage(merchantPhone, message);
                log.info("Requirement change notification sent to merchant: {} for transaction: {}", 
                        merchantPhone, transactionId);
            }
            
        } catch (Exception e) {
            log.error("Failed to send requirement change notification for transaction: {}", transactionId, e);
        }
    }

    /**
     * Builds missing documents notification message.
     */
    private String buildMissingDocumentsMessage(Transaction transaction, List<DocumentType> missingDocuments) {
        StringBuilder message = new StringBuilder();
        message.append("🔔 *Document Required* - Transaction ").append(transaction.getReferenceNumber()).append("\n\n");
        message.append("Dear ").append(transaction.getMerchant().getFullName()).append(",\n\n");
        message.append("Your cross-border payment requires the following documents:\n\n");
        
        for (int i = 0; i < missingDocuments.size(); i++) {
            DocumentType doc = missingDocuments.get(i);
            message.append("📄 ").append(i + 1).append(". ").append(doc.getDisplayName()).append("\n");
        }
        
        message.append("\n💰 *Payment Details:*\n");
        message.append("Amount: ").append(transaction.getCurrency()).append(" ").append(transaction.getAmount()).append("\n");
        message.append("Beneficiary: ").append(transaction.getBeneficiaryName()).append("\n\n");
        
        message.append("📲 Please upload the required documents through your Aegis-X dashboard to proceed with your payment.\n\n");
        message.append("Need help? Contact our support team.\n\n");
        message.append("Thank you,\nAegis-X Team");
        
        return message.toString();
    }

    /**
     * Builds evidence completion notification message.
     */
    private String buildEvidenceCompleteMessage(Transaction transaction) {
        StringBuilder message = new StringBuilder();
        message.append("✅ *Documents Complete* - Transaction ").append(transaction.getReferenceNumber()).append("\n\n");
        message.append("Dear ").append(transaction.getMerchant().getFullName()).append(",\n\n");
        message.append("Great news! All required documents have been received for your payment.\n\n");
        
        message.append("💰 *Payment Details:*\n");
        message.append("Amount: ").append(transaction.getCurrency()).append(" ").append(transaction.getAmount()).append("\n");
        message.append("Beneficiary: ").append(transaction.getBeneficiaryName()).append("\n\n");
        
        message.append("🔄 Your payment is now being processed through our compliance checks.\n");
        message.append("You will receive another notification once the processing is complete.\n\n");
        message.append("Thank you,\nAegis-X Team");
        
        return message.toString();
    }

    /**
     * Builds requirement change notification message.
     */
    private String buildRequirementChangeMessage(Transaction transaction, List<DocumentType> missingDocuments, String reason) {
        StringBuilder message = new StringBuilder();
        message.append("📋 *Document Requirements Updated* - Transaction ").append(transaction.getReferenceNumber()).append("\n\n");
        message.append("Dear ").append(transaction.getMerchant().getFullName()).append(",\n\n");
        
        if (reason != null && !reason.trim().isEmpty()) {
            message.append("Reason for change: ").append(reason).append("\n\n");
        }
        
        if (missingDocuments.isEmpty()) {
            message.append("✅ All required documents are now complete.\n");
        } else {
            message.append("The following additional documents are now required:\n\n");
            for (int i = 0; i < missingDocuments.size(); i++) {
                DocumentType doc = missingDocuments.get(i);
                message.append("📄 ").append(i + 1).append(". ").append(doc.getDisplayName()).append("\n");
            }
        }
        
        message.append("\n💰 *Payment Details:*\n");
        message.append("Amount: ").append(transaction.getCurrency()).append(" ").append(transaction.getAmount()).append("\n");
        message.append("Beneficiary: ").append(transaction.getBeneficiaryName()).append("\n\n");
        
        message.append("📲 Please check your Aegis-X dashboard for updated requirements.\n\n");
        message.append("Thank you,\nAegis-X Team");
        
        return message.toString();
    }

    /**
     * Gets merchant phone number from transaction.
     * In a real implementation, this would fetch from user profile or merchant data.
     */
    private String getMerchantPhoneNumber(Transaction transaction) {
        // For MVP, return a placeholder or extract from merchant profile
        // In production, this would come from User entity with phone field
        return "+1234567890"; // Placeholder for MVP
    }

    /**
     * Formats document list for notification display.
     */
    private String formatDocumentList(List<DocumentType> documents) {
        return documents.stream()
                .map(DocumentType::getDisplayName)
                .collect(Collectors.joining(", "));
    }
}