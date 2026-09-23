package com.aegisx.review.service;

import com.aegisx.audit.entity.ActorType;
import com.aegisx.audit.entity.AuditEventType;
import com.aegisx.audit.entity.AuditModule;
import com.aegisx.audit.service.AuditService;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.common.filter.CorrelationIdFilter;
import com.aegisx.review.dto.ReviewNoteResponse;
import com.aegisx.review.entity.ManualReviewNote;
import com.aegisx.review.repository.ManualReviewNoteRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.repository.TransactionRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Service for managing manual review notes.
 * 
 * Responsibilities:
 * - Create new review notes on transactions
 * - Retrieve review notes for transactions
 * - Track compliance officer investigation workflow
 * - Integrate with audit trail (every note creates an audit event)
 * 
 * Design Pattern: Service Facade
 * - Handles business logic and validation
 * - Coordinates with audit service
 * - Ensures immutability and RBAC
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewNoteService {

    private final ManualReviewNoteRepository noteRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    /**
     * Create a new manual review note on a transaction.
     * 
     * When a compliance officer adds a note:
     * 1. Note is persisted to database
     * 2. Audit event is created (MANUAL_REVIEW_NOTE_ADDED)
     * 3. Audit event includes note content and author
     * 4. This creates permanent record of investigation step
     *
     * @param transactionId the transaction to note
     * @param noteText the investigation note content
     * @param authorEmail the compliance officer's email (from security context)
     * @return the created note as DTO
     * @throws IllegalArgumentException if transaction not found
     */
    @Transactional
    public ReviewNoteResponse createNote(UUID transactionId, String noteText, String authorEmail) {
        log.info("Creating review note for transaction {}", transactionId);

        // Verify transaction exists
        Transaction transaction = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

        // Verify author (compliance officer) exists
        User author = userRepository.findByEmail(authorEmail)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + authorEmail));

        // Create note entity
        ManualReviewNote note = ManualReviewNote.builder()
            .transaction(transaction)
            .author(author)
            .noteText(noteText.trim())
            .build();

        // Persist note
        ManualReviewNote savedNote = noteRepository.save(note);

        // Create audit event for the note
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY);
        ObjectNode eventData = JsonNodeFactory.instance.objectNode();
        eventData.put("noteId", savedNote.getId().toString());
        eventData.put("noteLength", noteText.trim().length());
        eventData.put("authorId", author.getId().toString());

        auditService.recordSystemEvent(
            transaction,
            AuditEventType.MANUAL_REVIEW_NOTE_ADDED,
            AuditModule.SYSTEM,
            author.getId(),
            ActorType.USER,
            correlationId,
            eventData
        );

        log.info("Review note {} created by {} on transaction {}", 
            savedNote.getId(), author.getEmail(), transactionId);

        return ReviewNoteResponse.from(savedNote);
    }

    /**
     * Get all notes for a transaction, paginated and ordered chronologically.
     *
     * @param transactionId the transaction ID
     * @param pageable pagination settings
     * @return paginated notes for transaction, oldest first
     */
    @Transactional(readOnly = true)
    public Page<ReviewNoteResponse> getNotesForTransaction(UUID transactionId, Pageable pageable) {
        log.debug("Fetching review notes for transaction {} (page: {})", 
            transactionId, pageable.getPageNumber());

        Page<ManualReviewNote> notes = noteRepository.findByTransactionIdOrderByCreatedAtAsc(
            transactionId,
            pageable
        );

        return notes.map(ReviewNoteResponse::from);
    }

    /**
     * Get all notes written by a compliance officer.
     *
     * @param userId the compliance officer's ID
     * @param pageable pagination settings
     * @return paginated notes, newest first
     */
    @Transactional(readOnly = true)
    public Page<ReviewNoteResponse> getNotesFromUser(UUID userId, Pageable pageable) {
        log.debug("Fetching review notes from user {}", userId);

        Page<ManualReviewNote> notes = noteRepository.findByAuthorIdOrderByCreatedAtDesc(
            userId,
            pageable
        );

        return notes.map(ReviewNoteResponse::from);
    }

    /**
     * Count notes on a transaction.
     * Used for transaction summary display.
     *
     * @param transactionId the transaction ID
     * @return count of notes on transaction
     */
    @Transactional(readOnly = true)
    public long countNotesForTransaction(UUID transactionId) {
        return noteRepository.countByTransactionId(transactionId);
    }

    /**
     * Count notes written by a compliance officer.
     * Used for officer activity metrics.
     *
     * @param userId the user ID
     * @return count of notes written by user
     */
    @Transactional(readOnly = true)
    public long countNotesFromUser(UUID userId) {
        return noteRepository.countByAuthorId(userId);
    }
}
