package com.aegisx.review.controller;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.audit.entity.ActorType;
import com.aegisx.audit.entity.AuditEventType;
import com.aegisx.audit.repository.AuditEventRepository;
import com.aegisx.review.dto.ComplianceReviewRequest;
import com.aegisx.review.entity.ComplianceReview;
import com.aegisx.review.entity.ReviewDecision;
import com.aegisx.review.repository.ComplianceReviewRepository;
import com.aegisx.review.service.ComplianceReviewService;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ComplianceReviewControllerTest {

    private static final String REVIEWER_EMAIL = "reviewer@aegisx.test";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private ComplianceReviewRepository reviewRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private ComplianceReviewService reviewService;

    private User reviewer;
    private Transaction transaction;

    @BeforeEach
    void setUp() {
        reviewer = userRepository.saveAndFlush(User.builder()
                .fullName("Test Compliance Officer")
                .email(REVIEWER_EMAIL)
                .passwordHash("not-used-by-this-service")
                .role(Role.COMPLIANCE_OFFICER)
                .build());
        User merchant = userRepository.saveAndFlush(User.builder()
                .fullName("Test Merchant")
                .email("merchant-" + UUID.randomUUID() + "@aegisx.test")
                .passwordHash("not-used-by-this-service")
                .role(Role.MERCHANT)
                .build());
        transaction = createTransaction(merchant, TransactionStatus.COMPLIANCE_HOLD);
    }

    @Test
    @DisplayName("Reads the review queue from persisted held transactions")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_COMPLIANCE_OFFICER")
    void returnsReviewQueue() throws Exception {
        mockMvc.perform(get("/api/v1/compliance/queue").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].transactionId").value(transaction.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].referenceNumber").value(transaction.getReferenceNumber()))
                .andExpect(jsonPath("$.data.content[0].status").value("COMPLIANCE_HOLD"));
    }

    @Test
    @DisplayName("Reads the paginated shared unreviewed queue and legacy alias")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_COMPLIANCE_OFFICER")
    void returnsPersonalQueue() throws Exception {
        mockMvc.perform(get("/api/v1/compliance/queue/unreviewed").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].transactionId").value(transaction.getId().toString()));
        mockMvc.perform(get("/api/v1/compliance/queue/my").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].transactionId").value(transaction.getId().toString()));
    }

    @Test
    @DisplayName("Reads a persisted transaction as a review item")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_COMPLIANCE_OFFICER")
    void returnsReviewItem() throws Exception {
        mockMvc.perform(get("/api/v1/compliance/review/{transactionId}", transaction.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transactionId").value(transaction.getId().toString()));
    }

    @Test
    @DisplayName("Returns the documented error when the review item is absent")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_COMPLIANCE_OFFICER")
    void returnsNotFoundReviewItem() throws Exception {
        mockMvc.perform(get("/api/v1/compliance/review/{transactionId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Transaction not found or not in review queue"));
    }

    @Test
    @DisplayName("Submits and persists a review using the authenticated reviewer")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_COMPLIANCE_OFFICER")
    void submitsReview() throws Exception {
        mockMvc.perform(post("/api/v1/compliance/review")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(transaction.getId(),
                                "All compliance requirements are satisfied"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.decision").value("APPROVED"));

        assertThat(reviewRepository.findByTransactionId(transaction.getId()))
                .singleElement()
                .extracting(review -> review.getReviewer().getId())
                .isEqualTo(reviewer.getId());
        assertThat(transactionRepository.findById(transaction.getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.APPROVED);
        assertThat(auditEventRepository.findByTransactionIdOrderByCreatedAtAsc(transaction.getId()))
                .anySatisfy(event -> {
                    assertThat(event.getEventType()).isEqualTo(AuditEventType.MANUAL_REVIEW_DECISION);
                    assertThat(event.getActorType()).isEqualTo(ActorType.USER);
                    assertThat(event.getActorId()).isEqualTo(reviewer.getId());
                    assertThat(event.getPreviousState()).isEqualTo("COMPLIANCE_HOLD");
                    assertThat(event.getNewState()).isEqualTo("APPROVED");
                    assertThat(event.getEventData().get("decision").asText()).isEqualTo("APPROVED");
                    assertThat(event.getEventData().get("justification").asText())
                            .isEqualTo("All compliance requirements are satisfied");
                    assertThat(event.getCorrelationId()).isNotBlank();
                });
    }

    @Test
    @DisplayName("Validates review justification length")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_COMPLIANCE_OFFICER")
    void validatesJustification() throws Exception {
        mockMvc.perform(post("/api/v1/compliance/review")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(transaction.getId(), "Too short"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Reads persisted review history")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_COMPLIANCE_OFFICER")
    void returnsReviewHistory() throws Exception {
        reviewService.submitReview(transaction.getId(), reviewer.getId(),
                ReviewDecision.APPROVED, "History fixture review");

        mockMvc.perform(get("/api/v1/compliance/history/{transactionId}", transaction.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].decision").value("APPROVED"));
    }

    @Test
    @DisplayName("Returns reviewer statistics from persisted decisions")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_ADMIN")
    void returnsReviewerStatistics() throws Exception {
        ComplianceReview review = ComplianceReview.createReview(transaction, reviewer,
                TransactionStatus.COMPLIANCE_HOLD, ReviewDecision.APPROVED, "Recorded approval");
        // Keep the persisted fixture well inside the 30-day window instead of deriving it from the
        // exact clock tick used by the controller after the HTTP request starts.
        review.setReviewedAt(LocalDateTime.now().minusDays(1));
        reviewRepository.saveAndFlush(review);
        assertThat(reviewRepository.findByReviewerAndDateRange(reviewer.getId(),
                LocalDateTime.now().minusDays(2), LocalDateTime.now())).hasSize(1);

        mockMvc.perform(get("/api/v1/compliance/stats/{reviewerId}", reviewer.getId()).param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalReviews").value(1))
                .andExpect(jsonPath("$.data.approvalRate").value(100.0));
    }

    @Test
    @DisplayName("Clamps excessive review queue page sizes")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_COMPLIANCE_OFFICER")
    void clampsQueuePageSize() throws Exception {
        mockMvc.perform(get("/api/v1/compliance/queue").param("size", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageable.pageSize").value(100));
        mockMvc.perform(get("/api/v1/compliance/queue/unreviewed").param("size", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageable.pageSize").value(100));
        mockMvc.perform(get("/api/v1/compliance/history/{transactionId}", transaction.getId())
                        .param("size", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageable.pageSize").value(100));
    }

    @Test
    @DisplayName("Returns a stable second queue page instead of slicing a client subset")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_COMPLIANCE_OFFICER")
    void returnsSecondQueuePage() throws Exception {
        for (int index = 0; index < 20; index++) {
            createTransaction(transaction.getMerchant(), TransactionStatus.COMPLIANCE_HOLD);
        }

        mockMvc.perform(get("/api/v1/compliance/queue").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(21))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.content.length()").value(20));
        mockMvc.perform(get("/api/v1/compliance/queue").param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(21))
                .andExpect(jsonPath("$.data.content.length()").value(1));
    }

    @Test
    @DisplayName("Searches the entire pending queue on the server")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_COMPLIANCE_OFFICER")
    void searchesQueue() throws Exception {
        mockMvc.perform(get("/api/v1/compliance/queue").param("q", "test beneficiary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].transactionId").value(transaction.getId().toString()));
    }

    @Test
    @DisplayName("Rejects a decision when the reason is too short")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_COMPLIANCE_OFFICER")
    void rejectsShortDecisionReason() throws Exception {
        mockMvc.perform(post("/api/v1/compliance/review")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ComplianceReviewRequest.builder()
                                .transactionId(transaction.getId())
                                .decision(ReviewDecision.REJECTED)
                                .justification("No reason")
                                .build())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Persists a reasoned rejection and its human audit event")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_COMPLIANCE_OFFICER")
    void rejectsTransactionWithAudit() throws Exception {
        mockMvc.perform(post("/api/v1/compliance/review")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ComplianceReviewRequest.builder()
                                .transactionId(transaction.getId())
                                .decision(ReviewDecision.REJECTED)
                                .justification("Evidence conflicts with the approved beneficiary records.")
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("REJECTED"))
                .andExpect(jsonPath("$.data.resultingState").value("REJECTED"));
        assertThat(transactionRepository.findById(transaction.getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.REJECTED);
        assertThat(auditEventRepository.findByTransactionIdOrderByCreatedAtAsc(transaction.getId()))
                .anySatisfy(event -> {
                    assertThat(event.getEventType()).isEqualTo(AuditEventType.MANUAL_REVIEW_DECISION);
                    assertThat(event.getActorId()).isEqualTo(reviewer.getId());
                    assertThat(event.getNewState()).isEqualTo("REJECTED");
                });
    }

    @Test
    @DisplayName("Does not disclose another reviewer's statistics to an officer")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_COMPLIANCE_OFFICER")
    void hidesAnotherReviewersStatistics() throws Exception {
        mockMvc.perform(get("/api/v1/compliance/stats/{reviewerId}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Quick approval uses the real review workflow")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_COMPLIANCE_OFFICER")
    void quickApprovesTransaction() throws Exception {
        mockMvc.perform(post("/api/v1/compliance/quick-approve/{transactionId}", transaction.getId())
                        .with(csrf()).param("comments", "Standard approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.decision").value("APPROVED"));
    }

    @Test
    @DisplayName("Summarizes the real queue without materializing it")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_COMPLIANCE_OFFICER")
    void returnsQueueSummary() throws Exception {
        mockMvc.perform(get("/api/v1/compliance/queue/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.highPriorityItems").value(0))
                .andExpect(jsonPath("$.data.criticalViolations").value(0));
    }

    @Test
    @DisplayName("Requires authentication for compliance endpoints")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/compliance/queue"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Requires compliance officer or admin authority")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_MERCHANT")
    void requiresOfficerRole() throws Exception {
        mockMvc.perform(get("/api/v1/compliance/queue"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Maps invalid transaction states to a client error")
    @WithMockUser(username = REVIEWER_EMAIL, authorities = "ROLE_COMPLIANCE_OFFICER")
    void handlesInvalidState() throws Exception {
        Transaction approved = createTransaction(transaction.getMerchant(), TransactionStatus.APPROVED);
        mockMvc.perform(post("/api/v1/compliance/review")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(approved.getId(),
                                "Valid justification for review"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    private ComplianceReviewRequest request(UUID transactionId, String justification) {
        return ComplianceReviewRequest.builder()
                .transactionId(transactionId)
                .decision(ReviewDecision.APPROVED)
                .justification(justification)
                .build();
    }

    private Transaction createTransaction(User merchant, TransactionStatus status) {
        return transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-REVIEW-" + UUID.randomUUID())
                .merchant(merchant)
                .amount(new BigDecimal("5000.00"))
                .currency("USD")
                .beneficiaryName("Test Beneficiary")
                .purposeCode("P0101")
                .status(status)
                .version(0L)
                .build());
    }
}
