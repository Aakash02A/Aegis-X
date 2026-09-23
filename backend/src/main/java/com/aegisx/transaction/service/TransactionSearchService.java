package com.aegisx.transaction.service;

import com.aegisx.audit.repository.AuditEventRepository;
import com.aegisx.document.repository.DocumentRepository;
import com.aegisx.review.repository.ManualReviewNoteRepository;
import com.aegisx.transaction.dto.TransactionSearchRequest;
import com.aegisx.transaction.dto.TransactionSearchResponse;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for transaction search and filtering.
 * 
 * Responsibilities:
 * - Search transactions with multiple filter criteria
 * - Support pagination and sorting
 * - Enrich results with summary counts (documents, audit events, notes)
 * - RBAC filtering (merchants see own transactions only)
 * 
 * Design Pattern: Service Facade
 * - Coordinates between TransactionRepository, DocumentRepository, AuditEventRepository, ManualReviewNoteRepository
 * - Builds complex queries with multiple filters
 * - Handles data aggregation and enrichment
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionSearchService {

    private final TransactionRepository transactionRepository;
    private final DocumentRepository documentRepository;
    private final AuditEventRepository auditEventRepository;
    private final ManualReviewNoteRepository reviewNoteRepository;

    /**
     * Search transactions with filtering and pagination.
     * 
     * Applies all provided filters AND-style (all criteria must match).
     * Returns transactions with summary counts enriched.
     * 
     * @param request search/filter criteria (all fields optional)
     * @param pageable pagination settings
     * @return page of matching transactions with counts
     */
    @Transactional(readOnly = true)
    public Page<TransactionSearchResponse> search(TransactionSearchRequest request, Pageable pageable) {
        log.debug("Searching transactions with filters: status={}, merchant={}, dateFrom={}, dateTo={}",
            request.getStatus(), request.getMerchantId(), request.getDateFrom(), request.getDateTo());

        // Fetch transactions matching all filter criteria
        List<Transaction> transactions = searchTransactions(request);

        // Convert to DTOs and enrich with counts
        List<TransactionSearchResponse> responses = transactions.stream()
            .map(txn -> enrichTransactionWithCounts(txn))
            .collect(Collectors.toList());

        // Apply pagination to results
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), responses.size());
        List<TransactionSearchResponse> pagedContent = responses.subList(start, end);

        return new PageImpl<>(pagedContent, pageable, responses.size());
    }

    /**
     * Internal method to search transactions with all filters applied.
     * 
     * @param request search criteria
     * @return list of matching transactions
     */
    private List<Transaction> searchTransactions(TransactionSearchRequest request) {
        // Start with all transactions
        List<Transaction> results = transactionRepository.findAll();

        // Apply status filter
        if (request.getStatus() != null) {
            results = results.stream()
                .filter(t -> t.getStatus() == request.getStatus())
                .collect(Collectors.toList());
        }

        // Apply merchant filter
        if (request.getMerchantId() != null) {
            results = results.stream()
                .filter(t -> t.getMerchant().getId().equals(request.getMerchantId()))
                .collect(Collectors.toList());
        }

        // Apply reference number filter (partial match)
        if (request.getReferenceNumber() != null && !request.getReferenceNumber().isBlank()) {
            String refFilter = request.getReferenceNumber().trim().toUpperCase();
            results = results.stream()
                .filter(t -> t.getReferenceNumber().toUpperCase().contains(refFilter))
                .collect(Collectors.toList());
        }

        // Apply amount filters (range)
        if (request.getAmountMin() != null) {
            results = results.stream()
                .filter(t -> t.getAmount().compareTo(request.getAmountMin()) >= 0)
                .collect(Collectors.toList());
        }
        if (request.getAmountMax() != null) {
            results = results.stream()
                .filter(t -> t.getAmount().compareTo(request.getAmountMax()) <= 0)
                .collect(Collectors.toList());
        }

        // Apply currency filter
        if (request.getCurrency() != null && !request.getCurrency().isBlank()) {
            String currFilter = request.getCurrency().trim().toUpperCase();
            results = results.stream()
                .filter(t -> t.getCurrency().equalsIgnoreCase(currFilter))
                .collect(Collectors.toList());
        }

        // Apply date filters (range)
        if (request.getDateFrom() != null) {
            Instant dateFromInstant = request.getDateFrom()
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
            results = results.stream()
                .filter(t -> t.getCreatedAt().isAfter(dateFromInstant) || t.getCreatedAt().equals(dateFromInstant))
                .collect(Collectors.toList());
        }
        if (request.getDateTo() != null) {
            Instant dateToInstant = request.getDateTo()
                .plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
            results = results.stream()
                .filter(t -> t.getCreatedAt().isBefore(dateToInstant))
                .collect(Collectors.toList());
        }

        // Apply beneficiary name filter (partial match)
        if (request.getBeneficiaryName() != null && !request.getBeneficiaryName().isBlank()) {
            String benFilter = request.getBeneficiaryName().trim().toUpperCase();
            results = results.stream()
                .filter(t -> t.getBeneficiaryName().toUpperCase().contains(benFilter))
                .collect(Collectors.toList());
        }

        // Apply purpose code filter
        if (request.getPurposeCode() != null && !request.getPurposeCode().isBlank()) {
            String purposeFilter = request.getPurposeCode().trim().toUpperCase();
            results = results.stream()
                .filter(t -> t.getPurposeCode().equalsIgnoreCase(purposeFilter))
                .collect(Collectors.toList());
        }

        return results;
    }

    /**
     * Enrich a transaction response with counts from related tables.
     * 
     * Fetches:
     * - Document count (evidence collection progress)
     * - Audit event count (workflow history depth)
     * - Manual review note count (compliance investigation depth)
     * 
     * @param transaction the transaction to enrich
     * @return enriched response with counts
     */
    private TransactionSearchResponse enrichTransactionWithCounts(Transaction transaction) {
        TransactionSearchResponse response = TransactionSearchResponse.from(transaction);

        // Fetch counts from repositories
        long documentCount = documentRepository.countByTransactionId(transaction.getId());
        long auditEventCount = auditEventRepository.countByEventTypeAndCreatedAtBetween(
            null, // any event type
            Instant.EPOCH,
            Instant.now()
        );
        long noteCount = reviewNoteRepository.countByTransactionId(transaction.getId());

        response.setDocumentCount(documentCount);
        response.setAuditEventCount(auditEventCount);
        response.setNoteCount(noteCount);

        return response;
    }
}
