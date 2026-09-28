package com.aegisx.transaction.controller;

import com.aegisx.auth.entity.Role;
import com.aegisx.transaction.dto.TransactionResponse;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Local-only payment confirmation for exercising the transaction workflow without a Razorpay account. */
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
@Profile("dev")
public class LocalPaymentController {

    private final TransactionService transactionService;

    @PostMapping("/{id}/payment/confirm")
    @PreAuthorize("hasRole('MERCHANT')")
    public TransactionResponse confirmPayment(@PathVariable UUID id, Authentication authentication) {
        transactionService.getTransaction(id, authentication.getName(), Role.MERCHANT);
        transactionService.transitionStatus(id, TransactionStatus.PAYMENT_RECEIVED, "LOCAL_DEMO", "Local development payment confirmed");
        return transactionService.transitionStatus(id, TransactionStatus.EVIDENCE_PENDING, "LOCAL_DEMO", "Local development payment confirmed; evidence is now required");
    }
}
