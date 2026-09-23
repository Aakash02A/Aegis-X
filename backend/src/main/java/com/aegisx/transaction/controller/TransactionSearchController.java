package com.aegisx.transaction.controller;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.transaction.dto.TransactionSearchRequest;
import com.aegisx.transaction.dto.TransactionSearchResponse;
import com.aegisx.transaction.service.TransactionSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * REST controller for transaction search and filtering.
 * 
 * Endpoint:
 * - GET /api/transactions/search - Search transactions with filters
 * 
 * RBAC Rules:
 * - Merchants: Can only see their own transactions (merchantId filter ignored, filtered by current user)
 * - Compliance Officers: Can see all transactions and filter by any criteria
 * - Admins: Can see all transactions and filter by any criteria
 * 
 * Design Pattern: Resource Controller
 * Rationale: RESTful endpoint for transaction discovery.
 *           RBAC applied per endpoint.
 *           Query parameters for simple filtering.
 */
@Slf4j
@RestController
@RequestMapping("/api/transactions/search")
@RequiredArgsConstructor
public class TransactionSearchController {

    private final TransactionSearchService searchService;
    private final UserRepository userRepository;

    /**
     * Search transactions with flexible filtering and pagination.
     * 
     * RBAC:
     * - Merchants: Automatically filtered to own transactions (cannot override)
     * - Compliance Officers: Can filter by any criteria including merchant
     * - Admins: Can filter by any criteria including merchant
     * 
     * Query Parameters (all optional):
     * - status: Transaction status (CREATED, PAYMENT_RECEIVED, EVIDENCE_PENDING, OCR_PROCESSING, 
     *           AI_ANALYSIS, POLICY_EVALUATION, APPROVED, COMPLIANCE_HOLD)
     * - merchantId: Filter by merchant UUID (compliance officers only)
     * - reference: Partial transaction reference (e.g., "TXN-20260922")
     * - amountMin: Minimum amount (inclusive)
     * - amountMax: Maximum amount (inclusive)
     * - currency: 3-letter currency code (e.g., "USD")
     * - dateFrom: Start date (ISO format, inclusive)
     * - dateTo: End date (ISO format, inclusive)
     * - beneficiaryName: Partial beneficiary name match
     * - purposeCode: Transaction purpose (e.g., "CONSULTING", "SUPPLY")
     * - page: Page number (0-indexed, default 0)
     * - size: Page size (default 20, max 100)
     * 
     * Example Requests:
     * 
     * 1. Get all approved transactions from July 2026:
     *    GET /api/transactions/search?status=APPROVED&dateFrom=2026-07-01&dateTo=2026-07-31
     * 
     * 2. Get all high-value transactions ($10k+):
     *    GET /api/transactions/search?amountMin=10000&page=0&size=50
     * 
     * 3. Get transactions in hold status (compliance investigation):
     *    GET /api/transactions/search?status=COMPLIANCE_HOLD&dateFrom=2026-09-01
     * 
     * 4. Get transactions for specific merchant (compliance officer):
     *    GET /api/transactions/search?merchantId=550e8400-e29b-41d4-a716-446655440000
     * 
     * Response (200 OK):
     * {
     *   "content": [
     *     {
     *       "id": "550e8400-e29b-41d4-a716-446655440000",
     *       "referenceNumber": "TXN-20260923-001",
     *       "merchantId": "550e8400-e29b-41d4-a716-446655440001",
     *       "merchantEmail": "merchant@example.com",
     *       "amount": "5000.00",
     *       "currency": "USD",
     *       "status": "APPROVED",
     *       "beneficiaryName": "Acme Corp",
     *       "purposeCode": "SUPPLY",
     *       "createdAt": "2026-09-22T10:00:00Z",
     *       "updatedAt": "2026-09-22T14:30:00Z",
     *       "documentCount": 3,
     *       "auditEventCount": 12,
     *       "noteCount": 2,
     *       "lastEventType": "POLICY_APPROVED"
     *     }
     *   ],
     *   "totalElements": 2450,
     *   "totalPages": 49,
     *   "pageable": {
     *     "pageNumber": 0,
     *     "pageSize": 50,
     *     "sort": []
     *   }
     * }
     *
     * @param status transaction status (optional)
     * @param merchantId merchant UUID (optional, compliance officers only)
     * @param reference transaction reference (partial match, optional)
     * @param amountMin minimum amount (optional)
     * @param amountMax maximum amount (optional)
     * @param currency currency code (optional)
     * @param dateFrom start date (optional)
     * @param dateTo end date (optional)
     * @param beneficiaryName beneficiary name (partial match, optional)
     * @param purposeCode purpose code (optional)
     * @param authentication Spring Security context
     * @param pageable pagination settings
     * @return paginated transaction search results
     */
    @GetMapping
    public ResponseEntity<Page<TransactionSearchResponse>> searchTransactions(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) UUID merchantId,
        @RequestParam(required = false) String reference,
        @RequestParam(required = false) BigDecimal amountMin,
        @RequestParam(required = false) BigDecimal amountMax,
        @RequestParam(required = false) String currency,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(required = false) String beneficiaryName,
        @RequestParam(required = false) String purposeCode,
        Authentication authentication,
        Pageable pageable
    ) {
        log.info("GET /api/transactions/search - User: {} - Filters: status={}, merchant={}, dateFrom={}, dateTo={}",
            authentication.getName(), status, merchantId, dateFrom, dateTo);

        // Get current user for RBAC
        User currentUser = userRepository.findByEmail(authentication.getName())
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + authentication.getName()));

        // Build search request from query parameters
        TransactionSearchRequest.TransactionSearchRequestBuilder requestBuilder = TransactionSearchRequest.builder()
            .referenceNumber(reference)
            .amountMin(amountMin)
            .amountMax(amountMax)
            .currency(currency)
            .dateFrom(dateFrom)
            .dateTo(dateTo)
            .beneficiaryName(beneficiaryName)
            .purposeCode(purposeCode);

        // Parse status if provided
        if (status != null && !status.isBlank()) {
            try {
                requestBuilder.status(Enum.valueOf(com.aegisx.transaction.entity.TransactionStatus.class, status.toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid status filter: {}", status);
                // Invalid status - ignore and continue with other filters
            }
        }

        // RBAC: Apply merchant filter
        if (currentUser.getRole() == Role.MERCHANT) {
            // Merchants can only see their own transactions
            requestBuilder.merchantId(currentUser.getId());
            log.debug("Merchant {} filtered to own transactions", currentUser.getId());
        } else {
            // Compliance officers and admins can filter by any merchant
            requestBuilder.merchantId(merchantId);
        }

        TransactionSearchRequest request = requestBuilder.build();

        // Execute search
        Page<TransactionSearchResponse> results = searchService.search(request, pageable);

        log.info("Returning {} transactions (page {}/{})", 
            results.getNumberOfElements(), 
            pageable.getPageNumber() + 1, 
            results.getTotalPages());

        return ResponseEntity.ok(results);
    }
}
