package com.aegisx.transaction.controller;

import com.aegisx.auth.entity.Role;
import com.aegisx.transaction.dto.CreateTransactionRequest;
import com.aegisx.transaction.dto.TransactionResponse;
import com.aegisx.transaction.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping
    public ResponseEntity<TransactionResponse> createTransaction(
            @Valid @RequestBody CreateTransactionRequest request,
            Authentication authentication) {
        String email = authentication.getName();
        TransactionResponse response = transactionService.createTransaction(request, email);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<Page<TransactionResponse>> listTransactions(
            Authentication authentication,
            @PageableDefault(size = 20) Pageable pageable) {
        String email = authentication.getName();
        Role role = extractRole(authentication);
        Page<TransactionResponse> transactions = transactionService.listTransactions(email, role, pageable);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponse> getTransaction(
            @PathVariable UUID id,
            Authentication authentication) {
        String email = authentication.getName();
        Role role = extractRole(authentication);
        TransactionResponse response = transactionService.getTransaction(id, email, role);
        return ResponseEntity.ok(response);
    }

    private Role extractRole(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return Role.MERCHANT;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String auth = authority.getAuthority();
            if ("ROLE_ADMIN".equals(auth)) {
                return Role.ADMIN;
            }
            if ("ROLE_COMPLIANCE_OFFICER".equals(auth)) {
                return Role.COMPLIANCE_OFFICER;
            }
            if ("ROLE_MERCHANT".equals(auth)) {
                return Role.MERCHANT;
            }
        }
        return Role.MERCHANT;
    }
}
