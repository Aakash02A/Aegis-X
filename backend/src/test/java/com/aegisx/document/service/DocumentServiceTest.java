package com.aegisx.document.service;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.document.dto.EvidenceStatusResponse;
import com.aegisx.document.entity.DocumentType;
import com.aegisx.document.repository.DocumentRepository;
import com.aegisx.document.repository.RequiredDocumentRepository;
import com.aegisx.storage.service.StorageService;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private RequiredDocumentRepository requiredDocumentRepository;

    @Mock
    private StorageService storageService;

    @InjectMocks
    private DocumentService documentService;

    private Transaction transaction;
    private User merchant;

    @BeforeEach
    void setUp() {
        merchant = User.builder()
                .fullName("ABC Exports")
                .email("merchant@example.com")
                .passwordHash("hashed")
                .role(Role.MERCHANT)
                .build();
        merchant.setId(UUID.randomUUID());

        transaction = Transaction.builder()
                .referenceNumber("TXN-20260922-ABC123")
                .merchant(merchant)
                .amount(new BigDecimal("2500.00"))
                .currency("USD")
                .beneficiaryName("Blue Star Imports")
                .purposeCode("P0001")
                .status(TransactionStatus.EVIDENCE_PENDING)
                .version(0L)
                .build();
        transaction.setId(UUID.randomUUID());
    }

    @Test
    void initializeChecklistCreatesRequiredEvidenceRowsForTransaction() {
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(requiredDocumentRepository.findByTransactionId(transaction.getId())).thenReturn(List.of());

        documentService.initializeChecklist(transaction.getId());

        verify(requiredDocumentRepository).saveAll(anyList());
    }

    @Test
    void evidenceStatusReflectsCompletedChecklist() {
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(userRepository.findByEmail("merchant@example.com")).thenReturn(Optional.of(merchant));
        when(requiredDocumentRepository.findByTransactionId(transaction.getId())).thenReturn(List.of(
                new com.aegisx.document.entity.RequiredDocument(),
                new com.aegisx.document.entity.RequiredDocument()
        ));
        EvidenceStatusResponse response = documentService.getEvidenceStatus(transaction.getId(), "merchant@example.com");

        assertNotNull(response);
        assertEquals(2, response.getTotalRequired());
    }
}
