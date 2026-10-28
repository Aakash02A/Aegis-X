package com.aegisx.audit.controller;

import com.aegisx.audit.dto.AuditEventResponse;
import com.aegisx.audit.dto.AuditTimelineResponse;
import com.aegisx.audit.entity.AuditEvent;
import com.aegisx.audit.entity.AuditEventType;
import com.aegisx.audit.entity.AuditModule;
import com.aegisx.audit.repository.AuditEventRepository;
import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST API controller for audit timeline and audit trail queries.
 * 
 * This controller exposes immutable audit trails for transactions, enabling:
 * - Compliance officers and auditors to view complete audit history
 * - Merchants to view their own transaction timelines
 * - Root cause analysis of failures
 * - Regulatory explainability of decisions
 * 
 * Endpoints:
 * - GET /api/transactions/{id}/audit - Get complete timeline for a transaction
 * - GET /api/audit/search - Search audit events (with filters, RBAC-protected)
 * 
 * RBAC Rules:
 * - Merchant (MERCHANT role):
 *   * Can view audit timeline for their own transactions only
 *   * Cannot search across all transactions
 * - Compliance Officer (COMPLIANCE_OFFICER role):
 *   * Can view audit timeline for all transactions
 *   * Can search audit events with filters
 * - Administrator (ADMIN role):
 *   * Full access to all audit data and search functions
 * 
 * Design Pattern: Resource Controller
 * Rationale: RESTful API for audit data.
 *           Immutable read-only endpoints (no POST/PUT/DELETE).
 *           RBAC applied per endpoint.
 *           Consistent error handling (404 for unauthorized or not found).
 */
@Slf4j
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class AuditController {

    private final AuditEventRepository auditEventRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;

    /**
     * Get the complete immutable audit timeline for a transaction.
     * 
     * Timeline includes all business events in chronological order:
     * - Transaction creation
     * - Payment receipt
     * - Document uploads
     * - OCR completion
     * - AI analysis
     * - Policy decisions
     * - Notifications sent/received
     * 
     * Example Response:
     * {
     *   "transactionId": "550e8400-e29b-41d4-a716-446655440000",
     *   "transactionReference": "TXN-20260922-001",
     *   "merchantId": "550e8400-e29b-41d4-a716-446655440001",
     *   "currentState": "APPROVED",
     *   "events": [
     *     {
     *       "id": "550e8400-e29b-41d4-a716-446655440002",
     *       "eventType": "TRANSACTION_CREATED",
     *       "module": "TRANSACTION",
     *       "actorType": "USER",
     *       "correlationId": "req-001",
     *       "previousState": null,
     *       "newState": "CREATED",
     *       "createdAt": "2026-09-22T10:00:00Z"
     *     },
     *     {
     *       "id": "550e8400-e29b-41d4-a716-446655440003",
     *       "eventType": "PAYMENT_RECEIVED",
     *       "module": "RAZORPAY",
     *       "actorType": "WEBHOOK",
     *       "correlationId": "req-002",
     *       "previousState": "CREATED",
     *       "newState": "PAYMENT_RECEIVED",
     *       "eventData": {"paymentId": "pay_123456"},
     *       "createdAt": "2026-09-22T10:05:00Z"
     *     }
     *   ],
     *   "totalEvents": 12,
     *   "summary": "12 events: Created → Payment Received → Evidence Pending → OCR Processing → AI Analysis → Policy Approved"
     * }
     * 
     * RBAC:
     * - Merchant: Only own transactions
     * - Compliance Officer: All transactions
     * - Admin: All transactions
     * 
     * @param transactionId the transaction UUID
     * @param authentication Spring Security authentication (contains current user)
     * @param pageable pagination settings (optional, defaults to page 0, size 50)
     * @return AuditTimelineResponse with complete timeline or 404 if unauthorized/not found
     */
    @GetMapping("/{transactionId}/audit")
    public ResponseEntity<AuditTimelineResponse> getAuditTimeline(
        @PathVariable UUID transactionId,
        Authentication authentication,
        Pageable pageable
    ) {
        log.info("GET /api/transactions/{}/audit - User: {}", transactionId, authentication.getName());

        // Verify transaction exists
        Optional<Transaction> transactionOpt = transactionRepository.findById(transactionId);
        if (transactionOpt.isEmpty()) {
            log.warn("Audit timeline requested for non-existent transaction: {}", transactionId);
            return ResponseEntity.notFound().build();
        }

        Transaction transaction = transactionOpt.get();

        // RBAC: Check if user is authorized to view this transaction's audit trail
        User currentUser = userRepository.findByEmail(authentication.getName())
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + authentication.getName()));

        // Merchants can only view their own transactions
        if (currentUser.getRole() == Role.MERCHANT) {
            if (!transaction.getMerchant().getId().equals(currentUser.getId())) {
                log.warn("Unauthorized audit timeline request: User {} attempted to access transaction {} owned by {}",
                    currentUser.getId(), transactionId, transaction.getMerchant().getId());
                return ResponseEntity.notFound().build();
            }
        }
        // Compliance officers and admins can view all transactions

        // Fetch audit events for this transaction, paginated
        Page<AuditEvent> auditPage = auditEventRepository.findByTransactionIdOrderByCreatedAtAsc(
            transactionId,
            pageable
        );

        // Convert to DTOs
        List<AuditEventResponse> eventResponses = auditPage.getContent().stream()
            .map(this::toAuditEventResponse)
            .collect(Collectors.toList());

        // Build summary string
        String summary = buildTimelineSummary(eventResponses);

        // Build response
        AuditTimelineResponse response = AuditTimelineResponse.builder()
            .transactionId(transactionId)
            .transactionReference(transaction.getReferenceNumber())
            .merchantId(transaction.getMerchant().getId())
            .currentState(transaction.getStatus().name())
            .events(eventResponses)
            .totalEvents((int) auditPage.getTotalElements())
            .summary(summary)
            .build();

        log.info("Returning audit timeline for transaction {} with {} events", transactionId, eventResponses.size());
        return ResponseEntity.ok(response);
    }

    /**
     * Search audit events across transactions (RBAC-protected).
     * 
     * Query Parameters:
     * - transactionId (optional): Filter by transaction
     * - eventType (optional): Filter by event type
     * - module (optional): Filter by source module
     * - actor (optional): Filter by actor UUID
     * - startDate (optional): Filter by date range start
     * - endDate (optional): Filter by date range end
     * - page (optional): Page number (0-indexed)
     * - size (optional): Page size
     * 
     * RBAC:
     * - Merchant: Only sees own transactions' events (via implicit transactionId filter)
     * - Compliance Officer: Sees all events
     * - Admin: Sees all events
     * 
     * Example Request:
     * GET /api/audit/search?eventType=POLICY_APPROVED&module=POLICY_ENGINE&page=0&size=20
     * 
     * @param authentication Spring Security authentication
     * @param pageable pagination settings
     * @return paginated audit events
     */
    @GetMapping("/audit/search")
    public ResponseEntity<Page<AuditEventResponse>> searchAuditEvents(
        @RequestParam(required = false) UUID transactionId,
        @RequestParam(required = false) String eventType,
        @RequestParam(required = false) String module,
        @RequestParam(required = false) UUID actor,
        @RequestParam(required = false) String startDate,
        @RequestParam(required = false) String endDate,
        Authentication authentication,
        Pageable pageable
    ) {
        log.info("GET /api/audit/search - User: {}", authentication.getName());

        // Verify user role is authorized to search
        User currentUser = userRepository.findByEmail(authentication.getName())
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + authentication.getName()));

        // Only compliance officers and admins can search
        if (currentUser.getRole() == Role.MERCHANT) {
            log.warn("Unauthorized audit search: Merchant {} attempted to search audit events", currentUser.getId());
            return ResponseEntity.notFound().build();
        }

        AuditEventType parsedEventType = parseEventType(eventType);
        AuditModule parsedModule = parseModule(module);
        Instant rangeStart = parseDateBoundary(startDate, true, "startDate");
        Instant rangeEnd = parseDateBoundary(endDate, false, "endDate");
        if (rangeStart != null && rangeEnd != null && rangeStart.isAfter(rangeEnd)) {
            throw new IllegalArgumentException("startDate must not be after endDate");
        }

        // An unsorted page would come back in whatever order the database chooses, which makes a
        // paginated audit view unusable. Newest first is the operator's expectation.
        Pageable effectivePageable = pageable.getSort().isSorted()
            ? pageable
            : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<AuditEvent> specification = buildSearchSpecification(
            transactionId, parsedEventType, parsedModule, actor, rangeStart, rangeEnd);

        Page<AuditEvent> auditPage = auditEventRepository.findAll(specification, effectivePageable);
        Page<AuditEventResponse> responseEvents = auditPage.map(this::toAuditEventResponse);

        log.info("Returning {} audit events to user {}", responseEvents.getNumberOfElements(), authentication.getName());
        return ResponseEntity.ok(responseEvents);
    }

    /**
     * Builds the filter the search endpoint documents. Every filter is optional; an absent filter
     * contributes nothing rather than a wildcard comparison, so filtered and unfiltered searches
     * use the same code path.
     */
    private Specification<AuditEvent> buildSearchSpecification(UUID transactionId,
                                                               AuditEventType eventType,
                                                               AuditModule module,
                                                               UUID actorId,
                                                               Instant rangeStart,
                                                               Instant rangeEnd) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (transactionId != null) {
                predicates.add(criteriaBuilder.equal(root.get("transaction").get("id"), transactionId));
            }
            if (eventType != null) {
                predicates.add(criteriaBuilder.equal(root.get("eventType"), eventType));
            }
            if (module != null) {
                predicates.add(criteriaBuilder.equal(root.get("module"), module));
            }
            if (actorId != null) {
                predicates.add(criteriaBuilder.equal(root.get("actorId"), actorId));
            }
            if (rangeStart != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), rangeStart));
            }
            if (rangeEnd != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("createdAt"), rangeEnd));
            }
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    private AuditEventType parseEventType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AuditEventType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown eventType: " + value);
        }
    }

    private AuditModule parseModule(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AuditModule.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown module: " + value);
        }
    }

    /**
     * Accepts an ISO-8601 instant (2026-10-20T10:00:00Z), an offset date-time, a local date-time
     * (read as UTC, matching the database's UTC session), or a plain date. A date used as the end
     * of a range includes that whole day.
     */
    private Instant parseDateBoundary(String value, boolean startOfRange, String parameterName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // fall through to the other accepted shapes
        }
        try {
            return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through to a plain date
        }
        try {
            LocalDate date = LocalDate.parse(trimmed);
            return startOfRange
                ? date.atStartOfDay(ZoneOffset.UTC).toInstant()
                : date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                parameterName + " must be an ISO-8601 date or date-time, e.g. 2026-10-20 or 2026-10-20T10:00:00Z");
        }
    }

    /**
     * Convert AuditEvent entity to AuditEventResponse DTO.
     * Handles null safety and field mapping.
     *
     * @param event the audit event entity
     * @return the response DTO
     */
    private AuditEventResponse toAuditEventResponse(AuditEvent event) {
        return AuditEventResponse.builder()
            .id(event.getId())
            .transactionId(event.getTransaction().getId())
            .eventType(event.getEventType().name())
            .module(event.getModule().name())
            .actorId(event.getActorId())
            .actorType(event.getActorType().name())
            .correlationId(event.getCorrelationId())
            .previousState(event.getPreviousState())
            .newState(event.getNewState())
            .eventData(event.getEventData())
            .createdAt(event.getCreatedAt())
            .build();
    }

    /**
     * Build a human-readable summary of the audit timeline.
     * 
     * Example: "12 events: Created → Payment Received → Evidence Pending → OCR Processing → Policy Approved"
     * Truncates to first 5 state transitions to keep summary concise.
     *
     * @param events the list of audit events
     * @return human-readable summary string
     */
    private String buildTimelineSummary(List<AuditEventResponse> events) {
        if (events.isEmpty()) {
            return "No events recorded";
        }

        // Collect state transitions (events with new_state)
        List<String> states = events.stream()
            .filter(e -> e.getNewState() != null)
            .map(AuditEventResponse::getNewState)
            .limit(5)
            .collect(Collectors.toList());

        if (states.isEmpty()) {
            return String.format("%d events recorded", events.size());
        }

        String timeline = String.join(" → ", states);
        return String.format("%d events: %s", events.size(), timeline);
    }
}
