package com.aegisx.evidence.controller;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.evidence.dto.EvidenceChecklistResponse;
import com.aegisx.evidence.dto.EvidenceRequirementRequest;
import com.aegisx.evidence.dto.MissingDocumentsResponse;
import com.aegisx.evidence.entity.EvidenceChecklist;
import com.aegisx.evidence.service.EvidenceChecklistService;
import com.aegisx.auth.entity.User;
import com.aegisx.transaction.entity.Transaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EvidenceController.class)
class EvidenceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EvidenceChecklistService evidenceChecklistService;

    private UUID transactionId;
    private Transaction transaction;
    private EvidenceChecklist evidenceChecklist;
    private User merchant;

    @BeforeEach
    void setUp() {
        transactionId = UUID.randomUUID();
        
        merchant = User.builder()
                .fullName("test-merchant")
                .fullName("Test Merchant")
                .build();

        transaction = Transaction.builder()
                .referenceNumber("TXN-TEST-001")
                .merchant(merchant)
                .amount(new BigDecimal("1000.00"))
                .currency("USD")
                .beneficiaryName("Test Beneficiary")
                .build();

        evidenceChecklist = EvidenceChecklist.builder()
                .transaction(transaction)
                .invoiceRequired(true)
                .invoiceUploaded(false)
                .shippingBillRequired(false)
                .shippingBillUploaded(false)
                .isSatisfied(false)
                .evaluatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("Should get evidence checklist successfully")
    @WithMockUser(authorities = {"ROLE_MERCHANT"})
    void shouldGetEvidenceChecklistSuccessfully() throws Exception {
        when(evidenceChecklistService.getChecklistByTransactionId(transactionId))
                .thenReturn(Optional.of(evidenceChecklist));

        mockMvc.perform(get("/api/v1/evidence/checklist/{transactionId}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transactionId").value(transactionId.toString()))
                .andExpect(jsonPath("$.data.invoiceRequired").value(true))
                .andExpect(jsonPath("$.data.invoiceUploaded").value(false))
                .andExpect(jsonPath("$.data.satisfied").value(false));

        verify(evidenceChecklistService).getChecklistByTransactionId(transactionId);
    }

    @Test
    @DisplayName("Should return empty response when checklist not found")
    @WithMockUser(authorities = {"ROLE_MERCHANT"})
    void shouldReturnEmptyResponseWhenChecklistNotFound() throws Exception {
        when(evidenceChecklistService.getChecklistByTransactionId(transactionId))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/evidence/checklist/{transactionId}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isEmpty());

        verify(evidenceChecklistService).getChecklistByTransactionId(transactionId);
    }

    @Test
    @DisplayName("Should get missing documents successfully")
    @WithMockUser(authorities = {"ROLE_MERCHANT"})
    void shouldGetMissingDocumentsSuccessfully() throws Exception {
        List<DocumentType> missingTypes = List.of(DocumentType.COMMERCIAL_INVOICE, DocumentType.SHIPPING_BILL);
        
        when(evidenceChecklistService.getMissingDocuments(transactionId)).thenReturn(missingTypes);
        when(evidenceChecklistService.getChecklistByTransactionId(transactionId))
                .thenReturn(Optional.of(evidenceChecklist));

        mockMvc.perform(get("/api/v1/evidence/missing/{transactionId}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transactionId").value(transactionId.toString()))
                .andExpect(jsonPath("$.data.evidenceComplete").value(false))
                .andExpect(jsonPath("$.data.missingDocuments").isArray())
                .andExpect(jsonPath("$.data.missingDocuments.length()").value(2));

        verify(evidenceChecklistService).getMissingDocuments(transactionId);
    }

    @Test
    @DisplayName("Should update requirements successfully")
    @WithMockUser(authorities = {"ROLE_COMPLIANCE_OFFICER"})
    void shouldUpdateRequirementsSuccessfully() throws Exception {
        EvidenceRequirementRequest request = EvidenceRequirementRequest.builder()
                .transactionId(transactionId)
                .invoiceRequired(true)
                .shippingBillRequired(true)
                .packingListRequired(false)
                .certificateOriginRequired(false)
                .reason("High-value transaction")
                .build();

        EvidenceChecklist updatedChecklist = EvidenceChecklist.builder()
                .transaction(transaction)
                .invoiceRequired(true)
                .shippingBillRequired(true)
                .isSatisfied(false)
                .evaluatedAt(LocalDateTime.now())
                .build();

        when(evidenceChecklistService.setCustomRequirements(eq(transactionId), eq(true), eq(true), eq(false), eq(false)))
                .thenReturn(updatedChecklist);

        mockMvc.perform(put("/api/v1/evidence/requirements")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shippingBillRequired").value(true));

        verify(evidenceChecklistService).setCustomRequirements(transactionId, true, true, false, false);
    }

    @Test
    @DisplayName("Should mark document as uploaded successfully")
    @WithMockUser(authorities = {"ROLE_MERCHANT"})
    void shouldMarkDocumentAsUploadedSuccessfully() throws Exception {
        EvidenceChecklist updatedChecklist = EvidenceChecklist.builder()
                .transaction(transaction)
                .invoiceRequired(true)
                .invoiceUploaded(true)
                .isSatisfied(true)
                .evaluatedAt(LocalDateTime.now())
                .build();

        when(evidenceChecklistService.markDocumentUploaded(transactionId, DocumentType.COMMERCIAL_INVOICE))
                .thenReturn(updatedChecklist);

        mockMvc.perform(post("/api/v1/evidence/uploaded/{transactionId}", transactionId)
                        .with(csrf())
                        .param("documentType", "COMMERCIAL_INVOICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.invoiceUploaded").value(true))
                .andExpect(jsonPath("$.data.satisfied").value(true))
                .andExpect(jsonPath("$.message").value("Document uploaded and evidence checklist satisfied"));

        verify(evidenceChecklistService).markDocumentUploaded(transactionId, DocumentType.COMMERCIAL_INVOICE);
    }

    @Test
    @DisplayName("Should check evidence completeness successfully")
    @WithMockUser(authorities = {"ROLE_MERCHANT"})
    void shouldCheckEvidenceCompletenessSuccessfully() throws Exception {
        when(evidenceChecklistService.isEvidenceComplete(transactionId)).thenReturn(true);

        mockMvc.perform(get("/api/v1/evidence/complete/{transactionId}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(true))
                .andExpect(jsonPath("$.message").value("Evidence collection is complete"));

        verify(evidenceChecklistService).isEvidenceComplete(transactionId);
    }

    @Test
    @DisplayName("Should re-evaluate checklist successfully")
    @WithMockUser(authorities = {"ROLE_COMPLIANCE_OFFICER"})
    void shouldReEvaluateChecklistSuccessfully() throws Exception {
        when(evidenceChecklistService.reevaluateChecklist(transactionId)).thenReturn(evidenceChecklist);

        mockMvc.perform(post("/api/v1/evidence/reevaluate/{transactionId}", transactionId)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transactionId").value(transactionId.toString()));

        verify(evidenceChecklistService).reevaluateChecklist(transactionId);
    }

    @Test
    @DisplayName("Should get evidence summary successfully")
    @WithMockUser(authorities = {"ROLE_MERCHANT"})
    void shouldGetEvidenceSummarySuccessfully() throws Exception {
        List<DocumentType> missingTypes = List.of(DocumentType.COMMERCIAL_INVOICE);
        
        when(evidenceChecklistService.getMissingDocuments(transactionId)).thenReturn(missingTypes);
        when(evidenceChecklistService.isEvidenceComplete(transactionId)).thenReturn(false);

        mockMvc.perform(get("/api/v1/evidence/summary/{transactionId}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transactionId").value(transactionId.toString()))
                .andExpect(jsonPath("$.data.evidenceComplete").value(false))
                .andExpect(jsonPath("$.data.missingDocumentCount").value(1))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        verify(evidenceChecklistService).getMissingDocuments(transactionId);
        verify(evidenceChecklistService).isEvidenceComplete(transactionId);
    }

    @Test
    @DisplayName("Should require authentication for evidence endpoints")
    void shouldRequireAuthenticationForEvidenceEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/evidence/checklist/{transactionId}", transactionId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should require appropriate role for updating requirements")
    @WithMockUser(authorities = {"ROLE_MERCHANT"}) // Wrong role
    void shouldRequireAppropriateRoleForUpdatingRequirements() throws Exception {
        EvidenceRequirementRequest request = EvidenceRequirementRequest.builder()
                .transactionId(transactionId)
                .invoiceRequired(true)
                .build();

        mockMvc.perform(put("/api/v1/evidence/requirements")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verify(evidenceChecklistService, never()).setCustomRequirements(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should validate request data for updating requirements")
    @WithMockUser(authorities = {"ROLE_COMPLIANCE_OFFICER"})
    void shouldValidateRequestDataForUpdatingRequirements() throws Exception {
        EvidenceRequirementRequest request = EvidenceRequirementRequest.builder()
                // Missing transactionId
                .invoiceRequired(true)
                .build();

        mockMvc.perform(put("/api/v1/evidence/requirements")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(evidenceChecklistService, never()).setCustomRequirements(any(), any(), any(), any(), any());
    }
}