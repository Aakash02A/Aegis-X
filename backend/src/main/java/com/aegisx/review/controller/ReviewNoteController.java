package com.aegisx.review.controller;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.review.dto.CreateReviewNoteRequest;
import com.aegisx.review.dto.ReviewNoteResponse;
import com.aegisx.review.service.ReviewNoteService;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.repository.TransactionRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for manual review notes endpoints.
 * 
 * Endpoints:
 * - POST /api/transactions/{id}/notes - Create note (RBAC: compliance officer, admin)
 * - GET /api/transactions/{id}/notes - Get notes for transaction (RBAC: merchant own only, compliance all)
 * 
 * RBAC Rules:
 * - Merchants: Can create/view notes on their own transactions only
 * - Compliance Officers: Can create/view notes on all transactions
 * - Admins: Can create/view notes on all transactions
 * 
 * Design Pattern: Resource Controller
 * Rationale: RESTful endpoint for audit trail management.
 *           RBAC applied per endpoint.
 *           Consistent error handling.
 */
@Slf4j
@RestController
@RequestMapping("/api/transactions/{transactionId}/notes")
@RequiredArgsConstructor
public class ReviewNoteController {

    private final ReviewNoteService reviewNoteService;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;

    /**
     * Create a new manual review note on a transaction.
     * 
     * RBAC:
     * - Merchants: NOT allowed (use compliance officers for investigations)
     * - Compliance Officers: Allowed on all transactions
     * - Admins: Allowed on all transactions
     * 
     * Example Request:
     * POST /api/transactions/550e8400-e29b-41d4-a716-446655440000/notes
     * Content-Type: application/json
     * 
     * {
     *   "noteText": "Verified merchant identity via phone call. Consulting reference approved for 2025. Ready for settlement."
     * }
     * 
     * Response (201 Created):
     * {
     *   "id": "550e8400-e29b-41d4-a716-446655440001",
     *   "transactionId": "550e8400-e29b-41d4-a716-446655440000",
     *   "authorId": "550e8400-e29b-41d4-a716-446655440002",
     *   "authorName": "Sarah Johnson",
     *   "authorEmail": "sarah.johnson@compliance.com",
     *   "noteText": "Verified merchant identity...",
     *   "createdAt": "2026-09-23T10:30:00Z"
     * }
     *
     * @param transactionId the transaction to note
     * @param request the note content
     * @param authentication Spring Security authentication context
     * @return created note response
     */
    @PostMapping
    public ResponseEntity<ReviewNoteResponse> createNote(
        @PathVariable UUID transactionId,
        @Valid @RequestBody CreateReviewNoteRequest request,
        Authentication authentication
    ) {
        log.info("POST /api/transactions/{}/notes - User: {}", transactionId, authentication.getName());

        // Verify transaction exists
        Optional<Transaction> transactionOpt = transactionRepository.findById(transactionId);
        if (transactionOpt.isEmpty()) {
            log.warn("Attempted to create note on non-existent transaction: {}", transactionId);
            return ResponseEntity.notFound().build();
        }

        Transaction transaction = transactionOpt.get();

        // Get current user for RBAC
        User currentUser = userRepository.findByEmail(authentication.getName())
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + authentication.getName()));

        // RBAC: Only compliance officers and admins can create notes
        if (currentUser.getRole() == Role.MERCHANT) {
            log.warn("Unauthorized note creation: Merchant {} attempted to create note on transaction {}",
                currentUser.getId(), transactionId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // RBAC: Merchants cannot create notes (implicit above)
        // Compliance Officers can create notes on all transactions
        // Admins can create notes on all transactions

        // Create note via service
        ReviewNoteResponse note = reviewNoteService.createNote(
            transactionId,
            request.getNoteText(),
            authentication.getName()
        );

        log.info("Review note created: {} on transaction {}", note.getId(), transactionId);
        return ResponseEntity.status(HttpStatus.CREATED).body(note);
    }

    /**
     * Get all notes for a transaction.
     * 
     * RBAC:
     * - Merchants: Can view notes on own transactions only
     * - Compliance Officers: Can view notes on all transactions
     * - Admins: Can view notes on all transactions
     * 
     * Example Request:
     * GET /api/transactions/550e8400-e29b-41d4-a716-446655440000/notes?page=0&size=20
     * 
     * Response (200 OK):
     * {
     *   "content": [
     *     {
     *       "id": "...",
     *       "transactionId": "...",
     *       "authorId": "...",
     *       "authorName": "Sarah Johnson",
     *       "authorEmail": "sarah@compliance.com",
     *       "noteText": "First investigation finding",
     *       "createdAt": "2026-09-22T10:00:00Z"
     *     },
     *     {
     *       "id": "...",
     *       "transactionId": "...",
     *       "authorId": "...",
     *       "authorName": "Mike Chen",
     *       "authorEmail": "mike@compliance.com",
     *       "noteText": "Follow-up investigation",
     *       "createdAt": "2026-09-22T14:30:00Z"
     *     }
     *   ],
     *   "totalElements": 2,
     *   "totalPages": 1,
     *   "pageable": { "pageNumber": 0, "pageSize": 20 }
     * }
     *
     * @param transactionId the transaction ID
     * @param authentication Spring Security authentication context
     * @param pageable pagination settings
     * @return paginated notes for transaction
     */
    @GetMapping
    public ResponseEntity<Page<ReviewNoteResponse>> getNotes(
        @PathVariable UUID transactionId,
        Authentication authentication,
        Pageable pageable
    ) {
        log.info("GET /api/transactions/{}/notes - User: {}", transactionId, authentication.getName());

        // Verify transaction exists
        Optional<Transaction> transactionOpt = transactionRepository.findById(transactionId);
        if (transactionOpt.isEmpty()) {
            log.warn("Attempted to fetch notes for non-existent transaction: {}", transactionId);
            return ResponseEntity.notFound().build();
        }

        Transaction transaction = transactionOpt.get();

        // Get current user for RBAC
        User currentUser = userRepository.findByEmail(authentication.getName())
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + authentication.getName()));

        // RBAC: Merchants can only view notes on their own transactions
        if (currentUser.getRole() == Role.MERCHANT) {
            if (!transaction.getMerchant().getId().equals(currentUser.getId())) {
                log.warn("Unauthorized note access: Merchant {} attempted to view notes on transaction {} owned by {}",
                    currentUser.getId(), transactionId, transaction.getMerchant().getId());
                return ResponseEntity.notFound().build();
            }
        }
        // Compliance Officers and Admins can view notes on all transactions

        // Fetch notes via service
        Page<ReviewNoteResponse> notes = reviewNoteService.getNotesForTransaction(
            transactionId,
            pageable
        );

        log.info("Returning {} review notes for transaction {}", notes.getNumberOfElements(), transactionId);
        return ResponseEntity.ok(notes);
    }
}
