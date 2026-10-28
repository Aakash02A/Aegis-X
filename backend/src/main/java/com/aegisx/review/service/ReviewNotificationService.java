package com.aegisx.review.service;

import com.aegisx.notification.service.NotificationService;
import com.aegisx.review.entity.ComplianceReview;
import com.aegisx.review.entity.ReviewDecision;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Service for sending review-related notifications.
 * Integrates with WhatsApp notification service to inform merchants about review outcomes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewNotificationService {

    private final NotificationService notificationService;
    private final TransactionService transactionService;

    /**
     * Sends notification when transaction enters compliance hold.
     */
    public void sendComplianceHoldNotification(UUID transactionId) {
        log.info("Sending compliance hold notification for transaction: {}", transactionId);
        
        try {
            Transaction transaction = transactionService.getTransactionById(transactionId)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
            
            String message = buildComplianceHoldMessage(transaction);
            String merchantPhone = getMerchantPhoneNumber(transaction);
            
            if (merchantPhone != null) {
                notificationService.sendWhatsAppMessage(merchantPhone, message);
                log.info("Compliance hold notification sent to merchant: {} for transaction: {}", 
                        merchantPhone, transactionId);
            }
            
        } catch (Exception e) {
            log.error("Failed to send compliance hold notification for transaction: {}", transactionId, e);
        }
    }

    /**
     * Sends notification after compliance review decision.
     */
    public void sendReviewDecisionNotification(ComplianceReview review) {
        log.info("Sending review decision notification for transaction: {}, decision: {}", 
                review.getTransaction().getId(), review.getDecision());
        
        try {
            Transaction transaction = review.getTransaction();
            String message = buildReviewDecisionMessage(transaction, review);
            String merchantPhone = getMerchantPhoneNumber(transaction);
            
            if (merchantPhone != null) {
                notificationService.sendWhatsAppMessage(merchantPhone, message);
                log.info("Review decision notification sent to merchant: {} for transaction: {}", 
                        merchantPhone, transaction.getId());
            }
            
        } catch (Exception e) {
            log.error("Failed to send review decision notification for transaction: {}", 
                     review.getTransaction().getId(), e);
        }
    }

    /**
     * Sends notification to compliance officers about new items in review queue.
     */
    public void sendNewReviewItemNotification(UUID transactionId, String complianceOfficerPhone) {
        log.info("Sending new review item notification for transaction: {}", transactionId);
        
        try {
            Transaction transaction = transactionService.getTransactionById(transactionId)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
            
            String message = buildNewReviewItemMessage(transaction);
            
            if (complianceOfficerPhone != null) {
                notificationService.sendWhatsAppMessage(complianceOfficerPhone, message);
                log.info("New review item notification sent to compliance officer: {}", complianceOfficerPhone);
            }
            
        } catch (Exception e) {
            log.error("Failed to send new review item notification for transaction: {}", transactionId, e);
        }
    }

    /**
     * Builds compliance hold notification message.
     */
    private String buildComplianceHoldMessage(Transaction transaction) {
        StringBuilder message = new StringBuilder();
        message.append("⏳ *Payment Under Review* - Transaction ").append(transaction.getReferenceNumber()).append("\n\n");
        message.append("Dear ").append(transaction.getMerchant().getFullName()).append(",\n\n");
        message.append("Your payment is currently under compliance review as part of our security procedures.\n\n");
        
        message.append("💰 *Payment Details:*\n");
        message.append("Amount: ").append(transaction.getCurrency()).append(" ").append(transaction.getAmount()).append("\n");
        message.append("Beneficiary: ").append(transaction.getBeneficiaryName()).append("\n\n");
        
        message.append("🔍 *What's happening:*\n");
        message.append("• Our compliance team is reviewing your transaction\n");
        message.append("• This is a standard security procedure\n");
        message.append("• You will be notified once the review is complete\n\n");
        
        message.append("⏱️ *Expected timeframe:* 1-2 business days\n\n");
        message.append("If you have any questions, please contact our support team.\n\n");
        message.append("Thank you for your patience,\nAegis-X Compliance Team");
        
        return message.toString();
    }

    /**
     * Builds review decision notification message.
     */
    private String buildReviewDecisionMessage(Transaction transaction, ComplianceReview review) {
        StringBuilder message = new StringBuilder();
        
        if (review.getDecision() == ReviewDecision.APPROVED) {
            message.append("✅ *Payment Approved* - Transaction ").append(transaction.getReferenceNumber()).append("\n\n");
            message.append("Dear ").append(transaction.getMerchant().getFullName()).append(",\n\n");
            message.append("Great news! Your payment has been approved after compliance review.\n\n");
            
            message.append("💰 *Payment Details:*\n");
            message.append("Amount: ").append(transaction.getCurrency()).append(" ").append(transaction.getAmount()).append("\n");
            message.append("Beneficiary: ").append(transaction.getBeneficiaryName()).append("\n\n");
            
            message.append("🚀 *Next steps:*\n");
            message.append("• Your payment is now being processed for settlement\n");
            message.append("• You will receive confirmation once settlement is complete\n\n");
            
        } else {
            message.append("❌ *Payment Declined* - Transaction ").append(transaction.getReferenceNumber()).append("\n\n");
            message.append("Dear ").append(transaction.getMerchant().getFullName()).append(",\n\n");
            message.append("We regret to inform you that your payment could not be approved.\n\n");
            
            message.append("💰 *Payment Details:*\n");
            message.append("Amount: ").append(transaction.getCurrency()).append(" ").append(transaction.getAmount()).append("\n");
            message.append("Beneficiary: ").append(transaction.getBeneficiaryName()).append("\n\n");
            
            message.append("📋 *Reason:*\n");
            String truncatedJustification = review.getJustification().length() > 200 ? 
                    review.getJustification().substring(0, 200) + "..." : 
                    review.getJustification();
            message.append(truncatedJustification).append("\n\n");
            
            message.append("🔄 *Next steps:*\n");
            message.append("• Please contact our support team for assistance\n");
            message.append("• You may submit a new payment request if the issue is resolved\n\n");
        }
        
        message.append("For any questions, please contact our support team.\n\n");
        message.append("Best regards,\nAegis-X Compliance Team");
        
        return message.toString();
    }

    /**
     * Builds new review item notification for compliance officers.
     */
    private String buildNewReviewItemMessage(Transaction transaction) {
        StringBuilder message = new StringBuilder();
        message.append("🔔 *New Review Item* - Transaction ").append(transaction.getReferenceNumber()).append("\n\n");
        message.append("A new transaction requires compliance review.\n\n");
        
        message.append("💰 *Transaction Details:*\n");
        message.append("Amount: ").append(transaction.getCurrency()).append(" ").append(transaction.getAmount()).append("\n");
        message.append("Merchant: ").append(transaction.getMerchant().getFullName()).append("\n");
        message.append("Beneficiary: ").append(transaction.getBeneficiaryName()).append("\n");
        message.append("Purpose: ").append(transaction.getPurposeCode()).append("\n\n");
        
        message.append("📱 Please log into the Aegis-X dashboard to review this transaction.\n\n");
        message.append("Aegis-X Compliance System");
        
        return message.toString();
    }

    /**
     * Returns the merchant's WhatsApp number, or null when the account has none. A missing number
     * means no message is sent; it must never fall back to a placeholder recipient.
     */
    private String getMerchantPhoneNumber(Transaction transaction) {
        return transaction.getMerchant() != null ? transaction.getMerchant().getPhone() : null;
    }
}