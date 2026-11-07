package com.aegisx.transaction.service;

import com.aegisx.audit.entity.AuditEventType;
import com.aegisx.audit.entity.AuditModule;
import com.aegisx.audit.entity.ActorType;
import com.aegisx.audit.service.AuditService;
import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.common.exception.TransactionNotFoundException;
import com.aegisx.common.filter.CorrelationIdFilter;
import com.aegisx.document.service.DocumentService;
import com.aegisx.transaction.dto.CreateTransactionRequest;
import com.aegisx.transaction.dto.TransactionResponse;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.entity.TransitionActor;
import com.aegisx.transaction.repository.TransactionRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final TransactionStateMachine transactionStateMachine;
    private final DocumentService documentService;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional
    public TransactionResponse createTransaction(CreateTransactionRequest request, String userEmail) {
        User merchant = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + userEmail));

        String referenceNumber = generateReferenceNumber();
        String normalizedCurrency = request.getCurrency().trim().toUpperCase(Locale.ROOT);
        String normalizedPurposeCode = request.getPurposeCode().trim().toUpperCase(Locale.ROOT);

        Transaction transaction = Transaction.builder()
                .referenceNumber(referenceNumber)
                .merchant(merchant)
                .amount(request.getAmount())
                .currency(normalizedCurrency)
                .beneficiaryName(request.getBeneficiaryName().trim())
                .purposeCode(normalizedPurposeCode)
                .status(TransactionStatus.CREATED)
                .version(0L)
                .build();

        Transaction saved = transactionRepository.save(transaction);
        log.info("Created transaction with ID: {} and reference: {} for merchant: {}",
                saved.getId(), saved.getReferenceNumber(), merchant.getEmail());

        // Record audit event for transaction creation
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY);
        ObjectNode eventData = JsonNodeFactory.instance.objectNode();
        eventData.put("amount", request.getAmount().toString());
        eventData.put("currency", normalizedCurrency);
        eventData.put("beneficiaryName", request.getBeneficiaryName());
        eventData.put("purposeCode", normalizedPurposeCode);

        auditService.recordStateTransition(
            saved,
            AuditEventType.TRANSACTION_CREATED,
            AuditModule.TRANSACTION,
            merchant.getId(),
            ActorType.USER,
            correlationId,
            null,
            TransactionStatus.CREATED.name(),
            eventData
        );

        return TransactionResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public Transaction getAuthorizedTransaction(UUID transactionId, String userEmail, Role role) {
        if (role == Role.MERCHANT) {
            User user = userRepository.findByEmail(userEmail)
                    .orElseThrow(() -> new TransactionNotFoundException(transactionId));
            return transactionRepository.findByIdAndMerchantId(transactionId, user.getId())
                    .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        } else {
            return transactionRepository.findById(transactionId)
                    .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        }
    }

    @Transactional(readOnly = true)
    public Transaction getAuthorizedTransaction(UUID transactionId, org.springframework.security.core.Authentication authentication) {
        if (authentication == null) {
            throw new TransactionNotFoundException(transactionId);
        }
        String email = authentication.getName();
        Role role = Role.MERCHANT;
        if (authentication.getAuthorities() != null) {
            for (var authority : authentication.getAuthorities()) {
                String auth = authority.getAuthority();
                if ("ROLE_ADMIN".equals(auth)) {
                    role = Role.ADMIN;
                    break;
                }
                if ("ROLE_COMPLIANCE_OFFICER".equals(auth)) {
                    role = Role.COMPLIANCE_OFFICER;
                    break;
                }
                if ("ROLE_SETTLEMENT_OPERATOR".equals(auth)) {
                    role = Role.SETTLEMENT_OPERATOR;
                    break;
                }
            }
        }
        return getAuthorizedTransaction(transactionId, email, role);
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(UUID transactionId, String userEmail, Role role) {
        Transaction transaction = getAuthorizedTransaction(transactionId, userEmail, role);
        return TransactionResponse.from(transaction);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> listTransactions(String userEmail, Role role, Pageable pageable) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + userEmail));

        if (role == Role.MERCHANT) {
            return transactionRepository.findByMerchantId(user.getId(), pageable)
                    .map(TransactionResponse::from);
        } else {
            return transactionRepository.findAll(pageable)
                    .map(TransactionResponse::from);
        }
    }

    /** Compatibility lookup for modules that consume the entity form of a transaction. */
    @Transactional(readOnly = true)
    public java.util.Optional<Transaction> getTransactionById(UUID transactionId) {
        return transactionRepository.findById(transactionId);
    }

    /** Compatibility persistence entry point for existing orchestration services. */
    @Transactional
    public Transaction updateTransaction(Transaction transaction) {
        return transactionRepository.save(transaction);
    }

    /**
     * Internal lifecycle transition orchestrator with typed actor.
     */
    @Transactional
    public TransactionResponse transitionStatus(UUID transactionId, TransactionStatus targetStatus, TransitionActor actor, String reason) {
        return transitionStatus(transactionId, targetStatus, actor != null ? actor.name() : "SYSTEM", reason);
    }

    /**
     * Internal lifecycle transition orchestrator (to be called by Razorpay webhooks in Phase 06,
     * Document & AI pipelines in Phases 07-09, and the Policy Engine in Phase 10).
     */
    @Transactional
    public TransactionResponse transitionStatus(UUID transactionId, TransactionStatus targetStatus, String actor, String reason) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        TransactionStatus previousStatus = transaction.getStatus();
        transactionStateMachine.transition(transaction, targetStatus, actor, reason);
        if (targetStatus == TransactionStatus.EVIDENCE_PENDING) {
            documentService.initializeChecklist(transaction.getId());
        }
        Transaction updated = transactionRepository.save(transaction);

        // Record audit event for state transition
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY);
        ObjectNode eventData = JsonNodeFactory.instance.objectNode();
        eventData.put("actor", actor);
        eventData.put("reason", reason);

        auditService.recordStateTransition(
            updated,
            AuditEventType.TRANSACTION_STATE_CHANGED,
            AuditModule.TRANSACTION,
            null,
            ActorType.SYSTEM,
            correlationId,
            previousStatus.name(),
            targetStatus.name(),
            eventData
        );

        return TransactionResponse.from(updated);
    }


    private String generateReferenceNumber() {
        String datePart = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE);
        String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        return String.format("TXN-%s-%s", datePart, randomSuffix);
    }
}
