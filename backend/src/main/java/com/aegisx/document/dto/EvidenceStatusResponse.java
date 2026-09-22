package com.aegisx.document.dto;

import com.aegisx.transaction.entity.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceStatusResponse {
    private TransactionStatus transactionStatus;
    private int totalRequired;
    private int fulfilledCount;
    private int remainingCount;
    private boolean complete;
    private List<RequiredDocumentResponse> requiredDocuments;
}
