package com.aegisx.evidence.controller;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.document.entity.DocumentType;
import com.aegisx.evidence.entity.EvidenceChecklist;
import com.aegisx.evidence.repository.EvidenceChecklistRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EvidenceControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private EvidenceChecklistRepository checklistRepository;

    private UUID transactionId;

    @BeforeEach
    void setUp() {
        User merchant = userRepository.saveAndFlush(User.builder()
                .fullName("Evidence Test Merchant")
                .email("evidence-" + UUID.randomUUID() + "@aegisx.test")
                .passwordHash("not-used-by-this-service")
                .role(Role.MERCHANT)
                .build());
        Transaction transaction = transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-EVIDENCE-" + UUID.randomUUID())
                .merchant(merchant)
                .amount(new BigDecimal("1000.00"))
                .currency("USD")
                .beneficiaryName("Test Beneficiary")
                .purposeCode("P0101")
                .status(TransactionStatus.EVIDENCE_PENDING)
                .version(0L)
                .build());
        transactionId = transaction.getId();
        checklistRepository.saveAndFlush(checklist(transaction, true, false, false));
    }

    @Test
    @DisplayName("Reads the persisted evidence checklist")
    @WithMockUser(authorities = "ROLE_MERCHANT")
    void getsChecklist() throws Exception {
        mockMvc.perform(get("/api/v1/evidence/checklist/{transactionId}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transactionId").value(transactionId.toString()))
                .andExpect(jsonPath("$.data.invoiceRequired").value(true))
                .andExpect(jsonPath("$.data.invoiceUploaded").value(false))
                .andExpect(jsonPath("$.data.satisfied").value(false));
    }

    @Test
    @DisplayName("Returns a successful empty checklist response when none exists")
    @WithMockUser(authorities = "ROLE_MERCHANT")
    void returnsEmptyChecklist() throws Exception {
        mockMvc.perform(get("/api/v1/evidence/checklist/{transactionId}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("Lists persisted missing documents")
    @WithMockUser(authorities = "ROLE_MERCHANT")
    void listsMissingDocuments() throws Exception {
        checklistRepository.findByTransactionId(transactionId).orElseThrow().setShippingBillRequired(true);
        checklistRepository.flush();

        mockMvc.perform(get("/api/v1/evidence/missing/{transactionId}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionId").value(transactionId.toString()))
                .andExpect(jsonPath("$.data.evidenceComplete").value(false))
                .andExpect(jsonPath("$.data.missingDocuments.length()").value(2));
    }

    @Test
    @DisplayName("Updates document requirements through the real service")
    @WithMockUser(authorities = "ROLE_COMPLIANCE_OFFICER")
    void updatesRequirements() throws Exception {
        var request = com.aegisx.evidence.dto.EvidenceRequirementRequest.builder()
                .transactionId(transactionId)
                .invoiceRequired(true)
                .shippingBillRequired(true)
                .packingListRequired(false)
                .certificateOriginRequired(false)
                .reason("High-value transaction")
                .build();

        mockMvc.perform(put("/api/v1/evidence/requirements")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shippingBillRequired").value(true));
    }

    @Test
    @DisplayName("Persists a document-uploaded checklist state")
    @WithMockUser(authorities = "ROLE_MERCHANT")
    void marksDocumentUploaded() throws Exception {
        mockMvc.perform(post("/api/v1/evidence/uploaded/{transactionId}", transactionId)
                        .with(csrf())
                        .param("documentType", "COMMERCIAL_INVOICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoiceUploaded").value(true))
                .andExpect(jsonPath("$.data.satisfied").value(true))
                .andExpect(jsonPath("$.message").value("Document uploaded and evidence checklist satisfied"));
    }

    @Test
    @DisplayName("Reports evidence completeness from the persisted checklist")
    @WithMockUser(authorities = "ROLE_MERCHANT")
    void checksEvidenceCompleteness() throws Exception {
        checklistRepository.findByTransactionId(transactionId).orElseThrow().markDocumentUploaded(DocumentType.COMMERCIAL_INVOICE);
        checklistRepository.flush();

        mockMvc.perform(get("/api/v1/evidence/complete/{transactionId}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true))
                .andExpect(jsonPath("$.message").value("Evidence collection is complete"));
    }

    @Test
    @DisplayName("Re-evaluates and persists checklist satisfaction")
    @WithMockUser(authorities = "ROLE_COMPLIANCE_OFFICER")
    void reevaluatesChecklist() throws Exception {
        mockMvc.perform(post("/api/v1/evidence/reevaluate/{transactionId}", transactionId).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionId").value(transactionId.toString()));
    }

    @Test
    @DisplayName("Returns the merchant evidence summary")
    @WithMockUser(authorities = "ROLE_MERCHANT")
    void returnsSummary() throws Exception {
        mockMvc.perform(get("/api/v1/evidence/summary/{transactionId}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionId").value(transactionId.toString()))
                .andExpect(jsonPath("$.data.evidenceComplete").value(false))
                .andExpect(jsonPath("$.data.missingDocumentCount").value(1))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("Requires authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/evidence/checklist/{transactionId}", transactionId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Rejects requirement updates for merchants")
    @WithMockUser(authorities = "ROLE_MERCHANT")
    void rejectsWrongRole() throws Exception {
        var request = com.aegisx.evidence.dto.EvidenceRequirementRequest.builder()
                .transactionId(transactionId).invoiceRequired(true).build();
        mockMvc.perform(put("/api/v1/evidence/requirements")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Validates the required transaction id")
    @WithMockUser(authorities = "ROLE_COMPLIANCE_OFFICER")
    void validatesRequest() throws Exception {
        var request = com.aegisx.evidence.dto.EvidenceRequirementRequest.builder()
                .invoiceRequired(true).build();
        mockMvc.perform(put("/api/v1/evidence/requirements")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    private EvidenceChecklist checklist(Transaction transaction, boolean invoiceRequired,
                                         boolean shippingRequired, boolean invoiceUploaded) {
        EvidenceChecklist checklist = EvidenceChecklist.builder()
                .transaction(transaction)
                .invoiceRequired(invoiceRequired)
                .shippingBillRequired(shippingRequired)
                .invoiceUploaded(invoiceUploaded)
                .evaluatedAt(LocalDateTime.now())
                .build();
        checklist.evaluateSatisfaction();
        return checklist;
    }
}
