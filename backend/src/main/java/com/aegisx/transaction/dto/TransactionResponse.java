package com.aegisx.transaction.dto;

import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponse {

    private UUID id;
    private String referenceNumber;
    private UUID merchantId;
    private BigDecimal amount;
    private String currency;
    private String beneficiaryName;
    private String purposeCode;
    private TransactionStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    public static TransactionResponse from(Transaction transaction) {
        return TransactionResponse.builder()
                .id(transaction.getId())
                .referenceNumber(transaction.getReferenceNumber())
                .merchantId(transaction.getMerchant() != null ? transaction.getMerchant().getId() : null)
                .amount(transaction.getAmount())
                .currency(transaction.getCurrency())
                .beneficiaryName(transaction.getBeneficiaryName())
                .purposeCode(transaction.getPurposeCode())
                .status(transaction.getStatus())
                .createdAt(transaction.getCreatedAt())
                .updatedAt(transaction.getUpdatedAt())
                .build();
    }
}
