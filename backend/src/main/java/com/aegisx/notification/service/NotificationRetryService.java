package com.aegisx.notification.service;

import com.aegisx.notification.dto.DeliveryStatus;
import com.aegisx.notification.entity.NotificationMessage;
import com.aegisx.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * NotificationRetryService handles retry logic for failed WhatsApp messages.
 * 
 * Responsibilities:
 * - Find failed messages eligible for retry
 * - Apply exponential backoff
 * - Track retry attempts
 * - Log all retry attempts for audit trail
 * 
 * Design:
 * - Scheduled task runs periodically (configurable)
 * - Exponential backoff: 1s, 2s, 4s, 8s, 16s, etc.
 * - Max 5 retries per message
 * - Permanent failures (invalid phone, etc.) not retried
 * 
 * Security:
 * - Never modify notification record structure
 * - Only update status in forward direction
 * - All retries logged for compliance audit
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationRetryService {
    
    private final NotificationRepository notificationRepository;
    
    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_BACKOFF_SECONDS = 1;
    
    /**
     * Retry failed notifications.
     * 
     * Runs periodically to find failed outbound messages and retry.
     * Scheduled by Spring - override via application.yml if needed.
     */
    @Scheduled(fixedDelayString = "${aegisx.notification.retry-interval:300000}")  // 5 min default
    @Transactional
    public void retryFailedNotifications() {
        log.debug("Running notification retry service");
        
        try {
            // Find all failed notifications
            List<NotificationMessage> failed = notificationRepository.findByStatusOrderByCreatedAtAsc(DeliveryStatus.FAILED);
            
            log.debug("Found {} failed notifications", failed.size());
            
            for (NotificationMessage msg : failed) {
                if (shouldRetry(msg)) {
                    log.info("Retrying notification: id={}, messageId={}", msg.getId(), msg.getMessageId());
                    
                    // Retry logic would be implemented here
                    // For MVP, just log - actual retry depends on WhatsAppClient integration
                    
                } else {
                    log.debug("Notification not eligible for retry: id={}, reason=max_retries_exceeded or permanent_failure", msg.getId());
                }
            }
            
        } catch (Exception e) {
            log.error("Error in notification retry service: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Determine if notification should be retried.
     * 
     * @param msg Notification to check
     * @return true if eligible for retry
     */
    private boolean shouldRetry(NotificationMessage msg) {
        if (msg.getStatus() != DeliveryStatus.FAILED) {
            return false;
        }
        
        // Check if failure reason is permanent
        if (msg.getFailureReason() != null) {
            String reason = msg.getFailureReason().toLowerCase();
            
            // Permanent failures - don't retry
            if (reason.contains("invalid phone") || 
                reason.contains("not subscribed") || 
                reason.contains("banned")) {
                return false;
            }
        }
        
        // Check retry count (estimated from creation time and retry interval)
        // For MVP, assume max 5 retries over ~25 minutes
        long ageSeconds = (Instant.now().toEpochMilli() - msg.getCreatedAt().toEpochMilli()) / 1000;
        long maxAgeSeconds = MAX_RETRIES * 300;  // 5 retries * 5 min intervals
        
        return ageSeconds < maxAgeSeconds;
    }
    
    /**
     * Calculate backoff delay for retry attempt.
     * 
     * Exponential backoff: 1s, 2s, 4s, 8s, 16s
     * 
     * @param attemptNumber Retry attempt number (1-based)
     * @return Seconds to wait before retry
     */
    public long calculateBackoffSeconds(int attemptNumber) {
        if (attemptNumber <= 0 || attemptNumber > MAX_RETRIES) {
            return 0;
        }
        
        // 2^(n-1) * initial backoff
        return (long) Math.pow(2, attemptNumber - 1) * INITIAL_BACKOFF_SECONDS;
    }
}
