package com.aegisx.review.service;

import com.aegisx.auth.entity.User;
import com.aegisx.auth.service.UserService;
import com.aegisx.policy.entity.PolicyViolation;
import com.aegisx.policy.entity.ViolationSeverity;
import com.aegisx.policy.repository.PolicyViolationRepository;
import com.aegisx.review.dto.QueueSummary;
import com.aegisx.review.dto.ReviewQueueItemResponse;
import com.aegisx.review.entity.ComplianceReview;
import com.aegisx.review.entity.ReviewDecision;
import com.aegisx.review.repository.ComplianceReviewRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.entity.TransitionActor;
import com.aegisx.transaction.repository.TransactionRepository;
import com.aegisx.transaction.service.TransactionStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.EnumSet;

/**
 * Service for managing compliance officer reviews.
 * Implements human review workflow according to SRS Section 4.2.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ComplianceReviewService {

    private final ComplianceReviewRepository complianceReviewRepository;
    private final TransactionRepository transactionRepository;
    private final PolicyViolationRepository policyViolationRepository;
    private final com.aegisx.document.repository.RequiredDocumentRepository requiredDocumentRepository;
    private final UserService userService;
    private final TransactionStateMachine stateMachine;

    /**
     * Gets the review queue for compliance officers.
     * Returns transactions in COMPLIANCE_HOLD state ordered by priority.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public Page<ReviewQueueItemResponse> getReviewQueue(Pageable pageable) {
        return getReviewQueue(pageable, null);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public Page<ReviewQueueItemResponse> getReviewQueue(Pageable pageable, String filter) {
        log.debug("Getting review queue with page size: {}", pageable.getPageSize());
        Pageable ordered = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        if (filter == null || filter.isBlank() || "COMPLIANCE_HOLD".equalsIgnoreCase(filter)
                || "PENDING_REVIEW".equalsIgnoreCase(filter)) {
            return transactionRepository.findByStatus(TransactionStatus.COMPLIANCE_HOLD, ordered)
                    .map(this::buildReviewQueueItem);
        }
        if ("HIGH_RISK".equalsIgnoreCase(filter)) {
            return transactionRepository.findByAmountGreaterThanEqual(new java.math.BigDecimal("10000"), ordered)
                    .map(this::buildReviewQueueItem);
        }
        TransactionStatus status = switch (filter.toUpperCase(java.util.Locale.ROOT)) {
            case "OCR_FAILED" -> TransactionStatus.OCR_FAILED;
            case "AI_FAILED", "AI_ANALYSIS_FAILED" -> TransactionStatus.AI_ANALYSIS_FAILED;
            case "PAYMENT_FAILED" -> TransactionStatus.PAYMENT_FAILED;
            case "SETTLEMENT_FAILED" -> TransactionStatus.SETTLEMENT_FAILED;
            default -> throw new IllegalArgumentException("Unsupported review queue filter: " + filter);
        };
        return transactionRepository.findByStatus(status, ordered).map(this::buildReviewQueueItem);
    }

    /**
     * Gets pending review items for a specific compliance officer.
     *
     * <p>The "already reviewed" filter runs in the database. It used to load every transaction in
     * {@code COMPLIANCE_HOLD}, enrich each one (three extra queries per row) and then discard the
     * reviewed ones in memory, which made the endpoint's cost scale with the whole queue rather than
     * with the items it returns.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public List<ReviewQueueItemResponse> getMyReviewQueue(UUID reviewerId) {
        List<Transaction> transactions = transactionRepository.findUnreviewedByStatus(
                TransactionStatus.COMPLIANCE_HOLD);

        return transactions.stream()
                .map(this::buildReviewQueueItem)
                .toList();
    }

    /**
     * Computes the review-queue statistics without materialising the queue.
     *
     * <p>{@code highPriorityItems} is {@code priorityScore >= 80 || hasCriticalViolations}. With the
     * current weights the score is capped at 30 (time) + 25 (amount) + 25 (severity) = 80, so the
     * threshold is only reachable when all three maxima apply, and the severity maximum is CRITICAL —
     * which already sets {@code hasCriticalViolations}. The two counts therefore coincide by
     * construction, not by accident, and {@code ComplianceReviewServiceSummaryTest} pins the current
     * behaviour. Any change to the scoring weights makes this equivalence untrue, and this method
     * must then compute the score explicitly — the aggregate cannot silently inherit a new rule.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public QueueSummary summarizeQueue() {
        int totalItems = (int) transactionRepository.countByStatus(TransactionStatus.COMPLIANCE_HOLD);
        int criticalViolations = (int) policyViolationRepository
                .countTransactionsWithSeverityInStatus(TransactionStatus.COMPLIANCE_HOLD,
                        ViolationSeverity.CRITICAL);

        Optional<java.time.Instant> oldestHold = transactionRepository
                .findOldestUpdatedAtByStatus(TransactionStatus.COMPLIANCE_HOLD);
        long oldestItemHours = oldestHold
                .map(instant -> Math.max(0, ChronoUnit.HOURS.between(instant, java.time.Instant.now())))
                .orElse(0L);

        return QueueSummary.builder()
                .totalItems(totalItems)
                .highPriorityItems(criticalViolations)
                .criticalViolations(criticalViolations)
                .oldestItemHours(oldestItemHours)
                .build();
    }

    /**
     * Submits a compliance review decision.
     */
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ComplianceReview submitReview(UUID transactionId, UUID reviewerId, 
                                        ReviewDecision decision, String justification) {
        log.info("Submitting compliance review for transaction: {}, reviewer: {}, decision: {}", 
                transactionId, reviewerId, decision);
        
        // Validate inputs
        if (justification == null || justification.trim().length() < 10) {
            throw new IllegalArgumentException("Justification must be at least 10 characters long");
        }
        
        // Get transaction and validate state
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
        
        if (transaction.getStatus() != TransactionStatus.COMPLIANCE_HOLD) {
            throw new IllegalStateException("Transaction is not in COMPLIANCE_HOLD state: " + transaction.getStatus());
        }
        
        // Get reviewer
        User reviewer = userService.getUserById(reviewerId)
                .orElseThrow(() -> new IllegalArgumentException("Reviewer not found: " + reviewerId));
        
        // Create compliance review record
        ComplianceReview review = ComplianceReview.createReview(
                transaction, reviewer, TransactionStatus.COMPLIANCE_HOLD, decision, justification.trim());
        
        review.validateReview();
        ComplianceReview savedReview = complianceReviewRepository.save(review);
        
        // Update transaction status based on decision
        TransactionStatus targetStatus = decision == ReviewDecision.APPROVED ? 
                TransactionStatus.APPROVED : TransactionStatus.REJECTED;
        
        String reason = String.format("Manual review: %s - %s", 
                decision.getDisplayName(), 
                justification.substring(0, Math.min(100, justification.length())));
        
        // Use state machine helper methods for compliance reviews
        if (decision == ReviewDecision.APPROVED) {
            stateMachine.approveFromComplianceHold(transaction, reviewerId.toString(), justification);
        } else {
            stateMachine.rejectFromComplianceHold(transaction, reviewerId.toString(), justification);
        }
        
        transactionRepository.save(transaction);
        
        log.info("Compliance review submitted successfully for transaction: {}, outcome: {}", 
                transactionId, targetStatus);
        
        return savedReview;
    }

    /**
     * Gets review history for a transaction.
     */
    @Transactional(readOnly = true)
    public List<ComplianceReview> getReviewHistory(UUID transactionId) {
        return complianceReviewRepository.findByTransactionId(transactionId);
    }

    /**
     * Gets detailed information for a specific review queue item.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public Optional<ReviewQueueItemResponse> getReviewItem(UUID transactionId) {
        return transactionRepository.findById(transactionId)
                .map(this::buildReviewQueueItem);
    }

    /**
     * Gets statistics for compliance officer performance.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ReviewerStats getReviewerStats(UUID reviewerId, LocalDateTime since) {
        List<ComplianceReview> reviews = complianceReviewRepository.findByReviewerAndDateRange(
                reviewerId, since, LocalDateTime.now());
        
        long totalReviews = reviews.size();
        long approvals = reviews.stream().filter(ComplianceReview::isApproval).count();
        long rejections = reviews.stream().filter(ComplianceReview::isRejection).count();
        
        double approvalRate = totalReviews > 0 ? (approvals * 100.0 / totalReviews) : 0.0;
        
        return ReviewerStats.builder()
                .reviewerId(reviewerId)
                .totalReviews(totalReviews)
                .approvals(approvals)
                .rejections(rejections)
                .approvalRate(approvalRate)
                .periodStart(since)
                .periodEnd(LocalDateTime.now())
                .build();
    }

    /**
     * Builds a review queue item from transaction with additional context.
     */
    private ReviewQueueItemResponse buildReviewQueueItem(Transaction transaction) {
        ReviewQueueItemResponse item = ReviewQueueItemResponse.fromTransaction(transaction);

        // Evidence completeness comes from the evidence checklist, not from the transaction row.
        // Without this the queue reported every item as incomplete, including fully evidenced ones.
        var requiredDocuments = requiredDocumentRepository.findByTransactionId(transaction.getId());
        int missingDocuments = (int) requiredDocuments.stream()
                .filter(document -> !document.isFulfilled())
                .count();
        item.setMissingDocuments(missingDocuments);
        item.setEvidenceComplete(!requiredDocuments.isEmpty() && missingDocuments == 0);

        // Add violation information
        List<PolicyViolation> violations = policyViolationRepository.findByTransactionId(transaction.getId());
        item.setViolations(violations.stream()
                .map(ReviewQueueItemResponse.ViolationInfo::fromPolicyViolation)
                .toList());
        item.setTotalViolations(violations.size());

        ViolationSeverity highestSeverity = null;
        if (!violations.isEmpty()) {
            highestSeverity = violations.stream()
                    .map(PolicyViolation::getSeverity)
                    .max((s1, s2) -> Integer.compare(s1.getPriorityLevel(), s2.getPriorityLevel()))
                    .orElse(null);
            item.setHighestSeverity(highestSeverity);
        }

        // Add timing information
        LocalDateTime enteredHoldAt = getHoldEntryTime(transaction);
        item.setEnteredHoldAt(enteredHoldAt);

        if (enteredHoldAt != null) {
            // Measured between two instants. This used to compare the UTC wall-clock LocalDateTime
            // above with LocalDateTime.now(), which is the JVM's zone: on any host that is not UTC
            // the age of an item (and therefore its priority score and the queue's oldest age) was
            // shifted by the zone offset, so the same data classified differently per environment.
            long hoursInHold = Math.max(0,
                    ChronoUnit.HOURS.between(transaction.getUpdatedAt(), java.time.Instant.now()));
            item.setTimeInHold(formatDuration(hoursInHold));
            item.setPriorityScore(calculatePriorityScore(transaction, hoursInHold, highestSeverity));
        }
        
        // Add review status
        Optional<ComplianceReview> lastReview = complianceReviewRepository.findLatestByTransactionId(transaction.getId());
        if (lastReview.isPresent()) {
            item.setHasBeenReviewed(true);
            item.setLastReviewerName(lastReview.get().getReviewer().getFullName());
            item.setLastReviewedAt(lastReview.get().getReviewedAt());
        }
        
        return item;
    }

    /**
     * Gets the time when transaction entered COMPLIANCE_HOLD state.
     * In production, this would come from audit events.
     */
    private LocalDateTime getHoldEntryTime(Transaction transaction) {
        // For now, use updatedAt as approximation
        // In production, query audit_events for COMPLIANCE_HOLD transition
        return LocalDateTime.ofInstant(transaction.getUpdatedAt(), java.time.ZoneOffset.UTC);
    }

    /**
     * Calculates priority score based on various factors.
     */
    private int calculatePriorityScore(Transaction transaction, long hoursInHold,
                                       ViolationSeverity highestSeverity) {
        int score = 0;

        // Time-based scoring
        if (hoursInHold > 48) score += 30;      // > 2 days
        else if (hoursInHold > 24) score += 20; // > 1 day
        else if (hoursInHold > 8) score += 10;  // > 8 hours

        // Amount-based scoring
        if (transaction.getAmount().compareTo(new java.math.BigDecimal("10000")) > 0) {
            score += 25; // High-value transactions
        } else if (transaction.getAmount().compareTo(new java.math.BigDecimal("5000")) > 0) {
            score += 15; // Medium-value transactions
        }

        // Violation severity scoring, from the actual persisted findings.
        if (highestSeverity != null) {
            score += switch (highestSeverity) {
                case CRITICAL -> 25;
                case HIGH -> 15;
                case MEDIUM -> 8;
                case LOW -> 3;
            };
        }

        return Math.min(score, 100); // Cap at 100
    }

    /**
     * Formats duration in human-readable format.
     */
    private String formatDuration(long hours) {
        if (hours < 1) {
            return "< 1 hour";
        } else if (hours < 24) {
            return hours + " hours";
        } else {
            long days = hours / 24;
            long remainingHours = hours % 24;
            if (remainingHours == 0) {
                return days + " days";
            } else {
                return days + " days, " + remainingHours + " hours";
            }
        }
    }

    /**
     * Reviewer statistics DTO.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ReviewerStats {
        private UUID reviewerId;
        private long totalReviews;
        private long approvals;
        private long rejections;
        private double approvalRate;
        private LocalDateTime periodStart;
        private LocalDateTime periodEnd;
    }
}
