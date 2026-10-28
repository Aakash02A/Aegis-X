package com.aegisx.review.controller;

import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.common.response.ApiResponse;
import com.aegisx.review.dto.ComplianceReviewRequest;
import com.aegisx.review.dto.ComplianceReviewResponse;
import com.aegisx.review.dto.QueueSummary;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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
    private final UserRepository userRepository;

    @Operation(summary = "Get review queue", 
               description = "Retrieves transactions pending compliance review ordered by priority")
    @GetMapping("/queue")
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Page<ReviewQueueItemResponse>>> getReviewQueue(
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Optional queue filter: COMPLIANCE_HOLD, OCR_FAILED, AI_FAILED, PAYMENT_FAILED, SETTLEMENT_FAILED, HIGH_RISK, PENDING_REVIEW")
            @RequestParam(required = false) String filter) {
        
        // This controller builds its own PageRequest, so the global pageable cap does not apply.
        // Clamp here instead: an unbounded size would let a caller ask the server to materialise the
        // whole queue in one response.
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        log.debug("Getting review queue - page: {}, size: {}", safePage, safeSize);

        Pageable pageable = PageRequest.of(safePage, safeSize);
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
    @PreAuthorize("hasRole('ADMIN') or hasRole('COMPLIANCE_OFFICER')")
    public ResponseEntity<ApiResponse<ComplianceReviewService.ReviewerStats>> getReviewerStats(
            @Parameter(description = "Reviewer ID") @PathVariable UUID reviewerId,
            @Parameter(description = "Days to look back") @RequestParam(defaultValue = "30") int days,
            Authentication authentication) {
        
        UUID currentUserId = getUserIdFromAuthentication(authentication);
        if (!currentUserId.equals(reviewerId) && !hasRole(authentication, "ROLE_ADMIN")) {
            // A compliance officer may read their own statistics only. 404 rather than 403 keeps the
            // endpoint from confirming that another reviewer exists.
            log.warn("Reviewer {} attempted to read statistics for reviewer {}", currentUserId, reviewerId);
            return ResponseEntity.notFound().build();
        }
        
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
            // The client gets a fixed message: an arbitrary exception can quote SQL, file paths or
            // provider payloads, which is the same leak the global handler was fixed for.
            log.error("Error in quick approval", e);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Failed to approve transaction"));
        }
    }

    @Operation(summary = "Get queue summary",
               description = "Retrieves summary statistics for the review queue")
    @GetMapping("/queue/summary")
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<QueueSummary>> getQueueSummary() {
        
        log.debug("Getting queue summary");

        // Aggregated in the database. The previous implementation asked for
        // PageRequest.of(0, Integer.MAX_VALUE) and counted in Java, so the endpoint's cost grew with
        // the queue and could be used to force a full materialisation.
        QueueSummary summary = complianceReviewService.summarizeQueue();

        return ResponseEntity.ok(ApiResponse.success(summary, "Queue summary retrieved successfully"));
    }

    /**
     * Resolves the authenticated principal to the persisted user id.
     *
     * <p>The review trail is only audit-grade if the reviewer it names is the person the security
     * layer authenticated. This used to return a fixed placeholder UUID, which attributed every
     * decision to the same non-existent account.</p>
     */
    private UUID getUserIdFromAuthentication(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UsernameNotFoundException("No authenticated user");
        }
        return userRepository.findByEmail(authentication.getName())
                .map(User::getId)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "User not found: " + authentication.getName()));
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role::equals);
    }

    /**
     * Queue summary statistics.
     */
}
