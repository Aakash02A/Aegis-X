package com.aegisx.notification.service;

import com.aegisx.notification.dto.Direction;
import com.aegisx.notification.repository.NotificationRepository;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * TransactionMappingService maps inbound WhatsApp messages to transactions.
 * 
 * Priority:
 * 1. Extract transaction reference from message text (TXN-XXXXXXX-XXXXX)
 * 2. Find transaction by phone number (most recent EVIDENCE_PENDING or POLICY_EVALUATION)
 * 3. Return null (cannot map)
 * 
 * Design:
 * - Stateless lookups only (no state changes)
 * - Phone number -> Transaction mapping may return active pending transaction
 * - References are precise; phone numbers are probabilistic
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionMappingService {
    
    private final NotificationRepository notificationRepository;
    private final TransactionRepository transactionRepository;
    
    private static final Pattern TRANSACTION_REF_PATTERN = Pattern.compile("TXN-[A-Z0-9]+-[A-Z0-9]+");
    
    /**
     * Find transaction for inbound message.
     * 
     * Priority:
     * 1. Transaction reference in message
     * 2. Recent transaction for phone number
     * 3. null (cannot map)
     * 
     * @param phoneNumber Sender phone number
     * @param messageText Message content (optional)
     * @return Transaction ID or null
     */
    @Transactional(readOnly = true)
    public UUID findTransactionForPhone(String phoneNumber, String messageText) {
        if (phoneNumber == null) {
            log.debug("Cannot map transaction: phoneNumber is null");
            return null;
        }
        
        // Priority 1: Extract transaction reference from message
        if (messageText != null) {
            var matcher = TRANSACTION_REF_PATTERN.matcher(messageText);
            if (matcher.find()) {
                String refNumber = matcher.group();
                var transaction = transactionRepository.findByReferenceNumber(refNumber);
                if (transaction.isPresent()) {
                    log.info("Mapped transaction by reference: ref={}, txnId={}", refNumber, transaction.get().getId());
                    return transaction.get().getId();
                }
            }
        }
        
        // Priority 2: Find most recent active transaction for phone
        return findMostRecentTransactionForPhone(phoneNumber);
    }
    
    /**
     * Find transaction for inbound message (phone-based lookup only).
     * 
     * @param phoneNumber Sender phone number
     * @return Transaction ID or null
     */
    @Transactional(readOnly = true)
    public UUID findTransactionForPhone(String phoneNumber) {
        return findTransactionForPhone(phoneNumber, null);
    }
    
    /**
     * Find most recent active transaction for phone number.
     * 
     * Looks for recent outbound messages from this phone in EVIDENCE_PENDING or POLICY_EVALUATION status.
     * 
     * @param phoneNumber Phone number
     * @return Transaction ID or null
     */
    private UUID findMostRecentTransactionForPhone(String phoneNumber) {
        try {
            // Find most recent outbound message to this phone
            var recentMessage = notificationRepository.findFirstByRecipientOrderByCreatedAtDesc(phoneNumber);
            
            if (recentMessage.isPresent()) {
                UUID transactionId = recentMessage.get().getTransactionId();
                
                // Verify transaction is in active state
                var transaction = transactionRepository.findById(transactionId);
                
                if (transaction.isPresent() && isActiveState(transaction.get().getStatus())) {
                    log.info("Mapped transaction by phone: phone={}, txnId={}", phoneNumber, transactionId);
                    return transactionId;
                }
            }
            
            log.debug("No active transaction found for phone: phone={}", phoneNumber);
            return null;
            
        } catch (Exception e) {
            log.error("Error finding transaction for phone: phone={}, error={}", phoneNumber, e.getMessage());
            return null;
        }
    }
    
    private boolean isActiveState(TransactionStatus status) {
        return status == TransactionStatus.EVIDENCE_PENDING ||
               status == TransactionStatus.POLICY_EVALUATION ||
               status == TransactionStatus.OCR_PROCESSING ||
               status == TransactionStatus.AI_ANALYSIS;
    }
}
