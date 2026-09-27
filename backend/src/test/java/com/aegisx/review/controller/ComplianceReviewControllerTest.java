package com.aegisx.review.controller;

import com.aegisx.auth.entity.User;
import com.aegisx.review.dto.ComplianceReviewRequest;
import com.aegisx.review.dto.ReviewQueueItemResponse;
import com.aegisx.review.entity.ComplianceReview;
import com.aegisx.review.entity.ReviewDecision;
import com.aegisx.review.service.ComplianceReviewService;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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

@WebMvcTest(ComplianceReviewController.class)
class ComplianceReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ComplianceReviewService complianceReviewService;

    private UUID transactionId;
    private UUID reviewerId;
    private Transaction transaction;
    private User reviewer;
    private ComplianceReview review;
    private ReviewQueueItemResponse queueItem;

    @BeforeEach
    void setUp() {
        transactionId = UUID.randomUUID();
        reviewerId = UUID.randomUUID();
        
        reviewer = User.builder()
                .fullName("compliance-officer")
                .fullName("Test Compliance Officer")
                .build();

        transaction = Transaction.builder()
                .referenceNumber("TXN-TEST-001")
                .status(TransactionStatus.COMPLIANCE_HOLD)
                .amount(new BigDecimal("5000.00"))
                .currency("USD")
                .beneficiaryName("Test Beneficiary")
                .purposeCode("P0101")
                .build();

        review = ComplianceReview.builder()
                .transaction(transaction)
                .reviewer(reviewer)
                .previousState(TransactionStatus.COMPLIANCE_HOLD)
                .decision(ReviewDecision.APPROVED)
                .justification("All compliance requirements satisfied")
                .reviewedAt(LocalDateTime.now())
                .build();

        queueItem = ReviewQueueItemResponse.builder()
                .transactionId(transactionId)
                .referenceNumber("TXN-TEST-001")
                .status(TransactionStatus.COMPLIANCE_HOLD)
                .amount(new BigDecimal("5000.00"))
                .currency("USD")
                .beneficiaryName("Test Beneficiary")
                .merchantName("Test Merchant")
                .createdAt(LocalDateTime.now().minusHours(2))
                .priorityScore(75)
                .build();
    }

    @Test
    @DisplayName("Should get review queue successfully")
    @WithMockUser(authorities = {"ROLE_COMPLIANCE_OFFICER"})
    void shouldGetReviewQueueSuccessfully() throws Exception {
        Page<ReviewQueueItemResponse> queuePage = new PageImpl<>(List.of(queueItem), PageRequest.of(0, 20), 1);
        
        when(complianceReviewService.getReviewQueue(any())).thenReturn(queuePage);

        mockMvc.perform(get("/api/v1/compliance/queue")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].transactionId").value(transactionId.toString()))
                .andExpect(jsonPath("$.data.content[0].referenceNumber").value("TXN-TEST-001"))
                .andExpect(jsonPath("$.data.content[0].status").value("COMPLIANCE_HOLD"));

        verify(complianceReviewService).getReviewQueue(any());
    }

    @Test
    @DisplayName("Should get personal review queue successfully")
    @WithMockUser(authorities = {"ROLE_COMPLIANCE_OFFICER"})
    void shouldGetPersonalReviewQueueSuccessfully() throws Exception {
        when(complianceReviewService.getMyReviewQueue(any())).thenReturn(List.of(queueItem));

        mockMvc.perform(get("/api/v1/compliance/queue/my"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].transactionId").value(transactionId.toString()));

        verify(complianceReviewService).getMyReviewQueue(any());
    }

    @Test
    @DisplayName("Should get review item details successfully")
    @WithMockUser(authorities = {"ROLE_COMPLIANCE_OFFICER"})
    void shouldGetReviewItemDetailsSuccessfully() throws Exception {
        when(complianceReviewService.getReviewItem(transactionId)).thenReturn(Optional.of(queueItem));

        mockMvc.perform(get("/api/v1/compliance/review/{transactionId}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transactionId").value(transactionId.toString()));

        verify(complianceReviewService).getReviewItem(transactionId);
    }

    @Test
    @DisplayName("Should return error when review item not found")
    @WithMockUser(authorities = {"ROLE_COMPLIANCE_OFFICER"})
    void shouldReturnErrorWhenReviewItemNotFound() throws Exception {
        when(complianceReviewService.getReviewItem(transactionId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/compliance/review/{transactionId}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Transaction not found or not in review queue"));

        verify(complianceReviewService).getReviewItem(transactionId);
    }

    @Test
    @DisplayName("Should submit review successfully")
    @WithMockUser(authorities = {"ROLE_COMPLIANCE_OFFICER"})
    void shouldSubmitReviewSuccessfully() throws Exception {
        ComplianceReviewRequest request = ComplianceReviewRequest.builder()
                .transactionId(transactionId)
                .decision(ReviewDecision.APPROVED)
                .justification("All compliance requirements are satisfied after thorough review")
                .build();

        when(complianceReviewService.submitReview(eq(transactionId), any(), eq(ReviewDecision.APPROVED), any()))
                .thenReturn(review);

        mockMvc.perform(post("/api/v1/compliance/review")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.decision").value("APPROVED"));

        verify(complianceReviewService).submitReview(eq(transactionId), any(), eq(ReviewDecision.APPROVED), any());
    }

    @Test
    @DisplayName("Should validate justification length")
    @WithMockUser(authorities = {"ROLE_COMPLIANCE_OFFICER"})
    void shouldValidateJustificationLength() throws Exception {
        ComplianceReviewRequest request = ComplianceReviewRequest.builder()
                .transactionId(transactionId)
                .decision(ReviewDecision.APPROVED)
                .justification("Too short") // Less than 10 characters
                .build();

        mockMvc.perform(post("/api/v1/compliance/review")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(complianceReviewService, never()).submitReview(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should get review history successfully")
    @WithMockUser(authorities = {"ROLE_COMPLIANCE_OFFICER"})
    void shouldGetReviewHistorySuccessfully() throws Exception {
        when(complianceReviewService.getReviewHistory(transactionId)).thenReturn(List.of(review));

        mockMvc.perform(get("/api/v1/compliance/history/{transactionId}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].decision").value("APPROVED"));

        verify(complianceReviewService).getReviewHistory(transactionId);
    }

    @Test
    @DisplayName("Should get reviewer statistics successfully")
    @WithMockUser(authorities = {"ROLE_ADMIN"})
    void shouldGetReviewerStatisticsSuccessfully() throws Exception {
        ComplianceReviewService.ReviewerStats stats = ComplianceReviewService.ReviewerStats.builder()
                .reviewerId(reviewerId)
                .totalReviews(10)
                .approvals(8)
                .rejections(2)
                .approvalRate(80.0)
                .build();

        when(complianceReviewService.getReviewerStats(eq(reviewerId), any())).thenReturn(stats);

        mockMvc.perform(get("/api/v1/compliance/stats/{reviewerId}", reviewerId)
                        .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalReviews").value(10))
                .andExpect(jsonPath("$.data.approvalRate").value(80.0));

        verify(complianceReviewService).getReviewerStats(eq(reviewerId), any());
    }

    @Test
    @DisplayName("Should quick approve transaction successfully")
    @WithMockUser(authorities = {"ROLE_COMPLIANCE_OFFICER"})
    void shouldQuickApproveTransactionSuccessfully() throws Exception {
        when(complianceReviewService.submitReview(eq(transactionId), any(), eq(ReviewDecision.APPROVED), any()))
                .thenReturn(review);

        mockMvc.perform(post("/api/v1/compliance/quick-approve/{transactionId}", transactionId)
                        .with(csrf())
                        .param("comments", "Standard approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(complianceReviewService).submitReview(eq(transactionId), any(), eq(ReviewDecision.APPROVED), any());
    }

    @Test
    @DisplayName("Should get queue summary successfully")
    @WithMockUser(authorities = {"ROLE_COMPLIANCE_OFFICER"})
    void shouldGetQueueSummarySuccessfully() throws Exception {
        Page<ReviewQueueItemResponse> queuePage = new PageImpl<>(List.of(queueItem));
        when(complianceReviewService.getReviewQueue(any())).thenReturn(queuePage);

        mockMvc.perform(get("/api/v1/compliance/queue/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalItems").value(1));

        verify(complianceReviewService).getReviewQueue(any());
    }

    @Test
    @DisplayName("Should require authentication for compliance endpoints")
    void shouldRequireAuthenticationForComplianceEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/compliance/queue"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should require compliance officer role")
    @WithMockUser(authorities = {"ROLE_MERCHANT"}) // Wrong role
    void shouldRequireComplianceOfficerRole() throws Exception {
        mockMvc.perform(get("/api/v1/compliance/queue"))
                .andExpect(status().isForbidden());

        verify(complianceReviewService, never()).getReviewQueue(any());
    }

    @Test
    @DisplayName("Should handle service exceptions gracefully")
    @WithMockUser(authorities = {"ROLE_COMPLIANCE_OFFICER"})
    void shouldHandleServiceExceptionsGracefully() throws Exception {
        ComplianceReviewRequest request = ComplianceReviewRequest.builder()
                .transactionId(transactionId)
                .decision(ReviewDecision.APPROVED)
                .justification("Valid justification that is long enough for validation")
                .build();

        when(complianceReviewService.submitReview(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Transaction not in correct state"));

        mockMvc.perform(post("/api/v1/compliance/review")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}