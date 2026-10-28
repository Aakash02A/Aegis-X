package com.aegisx.review.service;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.policy.entity.PolicyEvaluation;
import com.aegisx.policy.entity.PolicyVerdict;
import com.aegisx.policy.entity.PolicyViolation;
import com.aegisx.policy.entity.ViolationSeverity;
import com.aegisx.policy.repository.PolicyEvaluationRepository;
import com.aegisx.policy.repository.PolicyViolationRepository;
import com.aegisx.review.dto.QueueSummary;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the aggregated review-queue summary.
 *
 * <p>The summary must use database aggregates rather than loading the full queue to count it.</p>
 */
@DisplayName("Review queue summary")
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:review_summary_test;DB_CLOSE_DELAY=-1")
@Transactional
@WithMockUser(roles = "COMPLIANCE_OFFICER")
class ComplianceReviewServiceSummaryTest {

    @Autowired private ComplianceReviewService service;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private PolicyEvaluationRepository evaluationRepository;
    @Autowired private PolicyViolationRepository violationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("counts and oldest age come from persisted records")
    void summaryIsComputedFromAggregates() {
        User merchant = merchant();
        Transaction oldest = null;
        Transaction firstCritical = null;
        Transaction secondCritical = null;
        for (int i = 0; i < 7; i++) {
            Transaction transaction = heldTransaction(merchant);
            if (i == 0) oldest = transaction;
            if (i == 1) firstCritical = transaction;
            if (i == 2) secondCritical = transaction;
        }

        addCriticalViolation(firstCritical, "CRITICAL-1");
        addCriticalViolation(firstCritical, "CRITICAL-2");
        addCriticalViolation(secondCritical, "CRITICAL-3");

        Instant oldestUpdatedAt = Instant.now().minus(52, ChronoUnit.HOURS);
        jdbcTemplate.update("UPDATE transactions SET updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(oldestUpdatedAt), oldest.getId());
        entityManager.clear();

        QueueSummary summary = service.summarizeQueue();

        assertThat(summary.getTotalItems()).isEqualTo(7);
        assertThat(summary.getCriticalViolations()).isEqualTo(2);
        assertThat(summary.getOldestItemHours())
                .as("age is derived from persisted instants and is independent of the host timezone")
                .isEqualTo(52L);
        assertThat(summary.getHighPriorityItems()).isEqualTo(summary.getCriticalViolations());
    }

    @Test
    @DisplayName("an empty queue reports zeros")
    void emptyQueueReportsZeros() {
        QueueSummary summary = service.summarizeQueue();

        assertThat(summary.getTotalItems()).isZero();
        assertThat(summary.getHighPriorityItems()).isZero();
        assertThat(summary.getCriticalViolations()).isZero();
        assertThat(summary.getOldestItemHours()).isZero();
    }

    private User merchant() {
        return userRepository.saveAndFlush(User.builder()
                .email("summary-" + UUID.randomUUID() + "@aegis-x.test")
                .fullName("Review Summary Test Merchant")
                .passwordHash("not-used-by-this-service")
                .role(Role.MERCHANT)
                .build());
    }

    private Transaction heldTransaction(User merchant) {
        return transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-SUMMARY-" + UUID.randomUUID())
                .merchant(merchant)
                .status(TransactionStatus.COMPLIANCE_HOLD)
                .amount(new BigDecimal("6000.00"))
                .currency("INR")
                .beneficiaryName("Summary Beneficiary")
                .purposeCode("P0102")
                .build());
    }

    private void addCriticalViolation(Transaction transaction, String ruleId) {
        PolicyEvaluation evaluation = evaluationRepository.saveAndFlush(PolicyEvaluation.createEvaluation(
                transaction, null, PolicyVerdict.HOLD, 1, 0, 1));
        violationRepository.saveAndFlush(PolicyViolation.createViolation(
                evaluation, ruleId, "Critical test rule", ViolationSeverity.CRITICAL,
                "Integration test critical violation", null));
    }
}
