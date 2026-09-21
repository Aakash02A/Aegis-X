package com.aegisx.transaction.service;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.common.exception.TransactionNotFoundException;
import com.aegisx.transaction.dto.CreateTransactionRequest;
import com.aegisx.transaction.dto.TransactionResponse;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.entity.TransitionActor;
import com.aegisx.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

        return TransactionResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(UUID transactionId, String userEmail, Role role) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + userEmail));

        Transaction transaction;
        if (role == Role.MERCHANT) {
            transaction = transactionRepository.findByIdAndMerchantId(transactionId, user.getId())
                    .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        } else {
            transaction = transactionRepository.findById(transactionId)
                    .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        }

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

        transactionStateMachine.transition(transaction, targetStatus, actor, reason);
        Transaction updated = transactionRepository.save(transaction);

        return TransactionResponse.from(updated);
    }


    private String generateReferenceNumber() {
        String datePart = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE);
        String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        return String.format("TXN-%s-%s", datePart, randomSuffix);
    }
}
