package com.aegisx.notification.repository;

import com.aegisx.notification.entity.NotificationMessage;
import com.aegisx.notification.dto.Direction;
import com.aegisx.notification.dto.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * NotificationRepository provides data access for notification messages.
 * 
 * Purpose:
 * - Query notification history for compliance audit trail
 * - Track delivery status for WhatsApp messages
 * - Find transactions by recipient phone for inbound message mapping
 */
@Repository
public interface NotificationRepository extends JpaRepository<NotificationMessage, UUID> {
    
    /**
     * Find all messages for a transaction (audit trail).
     * 
     * @param transactionId Transaction to query
     * @return All notifications for this transaction, ordered by creation time
     */
    List<NotificationMessage> findByTransactionIdOrderByCreatedAtDesc(UUID transactionId);
    
    /**
     * Find all outbound messages for a transaction (document requests).
     * 
     * @param transactionId Transaction to query
     * @return Outbound notifications (e.g., document requests)
     */
    List<NotificationMessage> findByTransactionIdAndDirectionOrderByCreatedAtDesc(
        UUID transactionId, 
        Direction direction
    );
    
    /**
     * Find message by Meta WhatsApp message ID (idempotency check).
     * 
     * @param messageId Meta WhatsApp message ID
     * @return Message if exists
     */
    Optional<NotificationMessage> findByMessageId(String messageId);
    
    /**
     * Find recent pending notifications (for retry logic).
     * 
     * @param status Delivery status to query
     * @return Messages with given status
     */
    List<NotificationMessage> findByStatusOrderByCreatedAtAsc(DeliveryStatus status);
    
    /**
     * Find inbound messages from recipient (for transaction mapping).
     * 
     * @param recipient Phone number or user identifier
     * @return All inbound messages from this recipient
     */
    List<NotificationMessage> findByRecipientAndDirectionOrderByCreatedAtDesc(
        String recipient,
        Direction direction
    );
    
    /**
     * Find active pending transaction by recipient (for inbound message routing).
     * Used when inbound message doesn't reference a transaction.
     * 
     * @param recipient Phone number or user identifier
     * @return Most recent active transaction associated with recipient
     */
    Optional<NotificationMessage> findFirstByRecipientOrderByCreatedAtDesc(String recipient);
}
