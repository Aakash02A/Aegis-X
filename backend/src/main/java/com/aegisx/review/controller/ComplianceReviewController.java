package com.aegisx.review.controller;

import com.aegisx.common.response.ApiResponse;
import com.aegisx.review.dto.ComplianceReviewRequest;
import com.aegisx.review.dto.ComplianceReviewResponse;
import com.aegisx.review.dto.ReviewQueueItemResponse;
import com.aegisx.review.entity.ComplianceReview;
import com.aegisx.review.service.ComplianceReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * REST Controller for Compliance Review Management.
 * Provides endpoints for compliance officers to review transactions and make decisions.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/compliance")
@RequiredArgsConstructor
@Tag(name = "Compliance Review", description = "Human review workflow for compliance officers")
public class ComplianceReviewController {

    private final ComplianceReviewService complianceReviewService;

    @Operation(summary = "Get review queue", 
               description = "Retrieves transactions pending compliance review ordered by priority")
    @GetMapping("/queue")
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Page<ReviewQueueItemResponse>>> getReviewQueue(
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Optional queue filter: COMPLIANCE_HOLD, OCR_FAILED, AI_FAILED, PAYMENT_FAILED, SETTLEMENT_FAILED, HIGH_RISK, PENDING_REVIEW")
            @RequestParam(required = false) String filter) {
        
        log.debug("Getting review queue - page: {}, size: {}", page, size);
        
        Pageable pageable = PageRequest.of(page, size);
        Page<ReviewQueueItemResponse> queue = complianceReviewService.getReviewQueue(pageable, filter);
        
        return ResponseEntity.ok(ApiResponse.success(queue, "Review queue retrieved successfully"));
    }

    @Operation(summary = "Get my review queue",
               description = "Retrieves unreviewed transactions for the current compliance officer")
    @GetMapping("/queue/my")
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<ReviewQueueItemResponse>>> getMyReviewQueue(
            Authentication authentication) {
        
        UUID reviewerId = getUserIdFromAuthentication(authentication);
        log.debug("Getting personal review queue for reviewer: {}", reviewerId);
        
        List<ReviewQueueItemResponse> myQueue = complianceReviewService.getMyReviewQueue(reviewerId);
        
        return ResponseEntity.ok(ApiResponse.success(myQueue, "Personal review queue retrieved successfully"));
    }

    @Operation(summary = "Get review item details",
               description = "Retrieves detailed information for a specific transaction in review")
    @GetMapping("/review/{transactionId}")
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ReviewQueueItemResponse>> getReviewItem(
            @Parameter(description = "Transaction ID") @PathVariable UUID transactionId) {
        
        log.debug("Getting review item details for transaction: {}", transactionId);
        
        Optional<ReviewQueueItemResponse> reviewItem = complianceReviewService.getReviewItem(transactionId);
        
        if (reviewItem.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error("Transaction not found or not in review queue"));
        }
        
        return ResponseEntity.ok(ApiResponse.success(reviewItem.get(), "Review item retrieved successfully"));
    }

    @Operation(summary = "Submit compliance review",
               description = "Submits approval or rejection decision for a transaction")
    @PostMapping("/review")
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ComplianceReviewResponse>> submitReview(
            @Valid @RequestBody ComplianceReviewRequest request,
            Authentication authentication) {
        
        UUID reviewerId = getUserIdFromAuthentication(authentication);
        
        log.info("Submitting compliance review for transaction: {}, reviewer: {}, decision: {}", 
                request.getTransactionId(), reviewerId, request.getDecision());
        
        try {
            request.validate();
            
            ComplianceReview review = complianceReviewService.submitReview(
                    request.getTransactionId(),
                    reviewerId,
                    request.getDecision(),
                    request.getTrimmedJustification()
            );
            
            ComplianceReviewResponse response = ComplianceReviewResponse.fromEntity(review);
            
            String message = String.format("Transaction %s successfully %s", 
                    review.getTransaction().getReferenceNumber(),
                    request.getDecision().getDisplayName().toLowerCase());
            
            return ResponseEntity.ok(ApiResponse.success(response, message));
            
        } catch (IllegalArgumentException e) {
            log.warn("Invalid review request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Invalid request: " + e.getMessage()));
                    
        } catch (IllegalStateException e) {
            log.warn("Invalid transaction state for review: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Transaction cannot be reviewed: " + e.getMessage()));
                    
        } catch (Exception e) {
            log.error("Error submitting compliance review", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Internal error occurred while processing review"));
        }
    }

    @Operation(summary = "Get review history",
               description = "Retrieves review history for a transaction")
    @GetMapping("/history/{transactionId}")
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<ComplianceReviewResponse>>> getReviewHistory(
            @Parameter(description = "Transaction ID") @PathVariable UUID transactionId) {
        
        log.debug("Getting review history for transaction: {}", transactionId);
        
        List<ComplianceReview> history = complianceReviewService.getReviewHistory(transactionId);
        List<ComplianceReviewResponse> responses = history.stream()
                .map(ComplianceReviewResponse::fromEntity)
                .toList();
        
        return ResponseEntity.ok(ApiResponse.success(responses, "Review history retrieved successfully"));
    }

    @Operation(summary = "Get reviewer statistics",
               description = "Retrieves performance statistics for a compliance officer")
    @GetMapping("/stats/{reviewerId}")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('COMPLIANCE_OFFICER') and #reviewerId == authentication.principal.id)")
    public ResponseEntity<ApiResponse<ComplianceReviewService.ReviewerStats>> getReviewerStats(
            @Parameter(description = "Reviewer ID") @PathVariable UUID reviewerId,
            @Parameter(description = "Days to look back") @RequestParam(defaultValue = "30") int days) {
        
        log.debug("Getting reviewer statistics for: {}, days: {}", reviewerId, days);
        
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        ComplianceReviewService.ReviewerStats stats = complianceReviewService.getReviewerStats(reviewerId, since);
        
        return ResponseEntity.ok(ApiResponse.success(stats, "Reviewer statistics retrieved successfully"));
    }

    @Operation(summary = "Get current reviewer stats",
               description = "Retrieves performance statistics for the current reviewer")
    @GetMapping("/stats/my")
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ComplianceReviewService.ReviewerStats>> getMyStats(
            @Parameter(description = "Days to look back") @RequestParam(defaultValue = "30") int days,
            Authentication authentication) {
        
        UUID reviewerId = getUserIdFromAuthentication(authentication);
        
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        ComplianceReviewService.ReviewerStats stats = complianceReviewService.getReviewerStats(reviewerId, since);
        
        return ResponseEntity.ok(ApiResponse.success(stats, "Your statistics retrieved successfully"));
    }

    @Operation(summary = "Quick approve transaction",
               description = "Quickly approves a transaction with standard justification")
    @PostMapping("/quick-approve/{transactionId}")
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ComplianceReviewResponse>> quickApprove(
            @Parameter(description = "Transaction ID") @PathVariable UUID transactionId,
            @Parameter(description = "Optional additional comments") @RequestParam(required = false) String comments,
            Authentication authentication) {
        
        UUID reviewerId = getUserIdFromAuthentication(authentication);
        
        log.info("Quick approving transaction: {} by reviewer: {}", transactionId, reviewerId);
        
        String justification = "Standard compliance review completed. All requirements satisfied.";
        if (comments != null && !comments.trim().isEmpty()) {
            justification += " Additional comments: " + comments.trim();
        }
        
        try {
            ComplianceReview review = complianceReviewService.submitReview(
                    transactionId, reviewerId, 
                    com.aegisx.review.entity.ReviewDecision.APPROVED, 
                    justification);
            
            ComplianceReviewResponse response = ComplianceReviewResponse.fromEntity(review);
            
            return ResponseEntity.ok(ApiResponse.success(response, "Transaction approved successfully"));
            
        } catch (Exception e) {
            log.error("Error in quick approval", e);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Failed to approve transaction: " + e.getMessage()));
        }
    }

    @Operation(summary = "Get queue summary",
               description = "Retrieves summary statistics for the review queue")
    @GetMapping("/queue/summary")
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<QueueSummary>> getQueueSummary() {
        
        log.debug("Getting queue summary");
        
        Page<ReviewQueueItemResponse> queue = complianceReviewService.getReviewQueue(
                PageRequest.of(0, Integer.MAX_VALUE));
        
        List<ReviewQueueItemResponse> items = queue.getContent();
        
        QueueSummary summary = QueueSummary.builder()
                .totalItems(items.size())
                .highPriorityItems((int) items.stream().filter(ReviewQueueItemResponse::isHighPriority).count())
                .criticalViolations((int) items.stream().filter(ReviewQueueItemResponse::hasCriticalViolations).count())
                .oldestItemHours(items.stream()
                        .filter(item -> item.getEnteredHoldAt() != null)
                        .mapToLong(item -> java.time.temporal.ChronoUnit.HOURS.between(
                                item.getEnteredHoldAt(), LocalDateTime.now()))
                        .max().orElse(0))
                .build();
        
        return ResponseEntity.ok(ApiResponse.success(summary, "Queue summary retrieved successfully"));
    }

    /**
     * Extracts user ID from Spring Security authentication.
     */
    private UUID getUserIdFromAuthentication(Authentication authentication) {
        // This is a placeholder - in production, extract from JWT or UserDetails
        // For now, return a test UUID
        return UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    }

    /**
     * Queue summary statistics.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class QueueSummary {
        private int totalItems;
        private int highPriorityItems;
        private int criticalViolations;
        private long oldestItemHours;
    }
}
