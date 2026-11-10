package com.aegisx.review.service;

import com.aegisx.auth.entity.User;
import com.aegisx.auth.entity.Role;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.document.repository.RequiredDocumentRepository;
import com.aegisx.policy.repository.PolicyViolationRepository;
import com.aegisx.review.dto.ReviewQueueItemResponse;
import com.aegisx.review.repository.ComplianceReviewRepository;
import com.aegisx.transaction.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Queue ageing must not depend on the host's timezone.
 *
 * <p>The held-since value is stored as an instant but was rendered as a UTC {@code LocalDateTime} and
 * then compared with {@code LocalDateTime.now()}, which is the JVM's zone. On any host that is not
 * UTC — a developer laptop, a container without {@code TZ}, a zone-configured base image — every
 * item's age was shifted by the zone offset, which changed {@code timeInHold} and the priority score
 * for identical data. This test asserts the invariant directly: the age is derived from instants.</p>
 */
@DisplayName("Review queue ageing is timezone independent")
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ComplianceReviewQueueAgingTest {

    @Autowired private ComplianceReviewService service;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("a transaction held for 30 hours reports 30 hours and its time-based score")
    @WithMockUser(roles = "COMPLIANCE_OFFICER")
    void ageIsMeasuredBetweenInstants() {
        User merchant = userRepository.saveAndFlush(User.builder()
                .email("merchant@aegis-x.test")
                .fullName("Aegis Exports")
                .passwordHash("not-used-by-this-service")
                .role(Role.MERCHANT)
                .build());
        Instant createdAt = Instant.now().minus(40, ChronoUnit.HOURS);
        Instant updatedAt = Instant.now().minus(30, ChronoUnit.HOURS);
        Transaction transaction = transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-AGE-01")
                .merchant(merchant)
                .status(TransactionStatus.COMPLIANCE_HOLD)
                .amount(new BigDecimal("6000.00"))
                .currency("INR")
                .beneficiaryName("Beneficiary")
                .purposeCode("P0102")
                .build());
        jdbcTemplate.update("UPDATE transactions SET created_at = ?, updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(updatedAt), transaction.getId());
        entityManager.clear();

        ReviewQueueItemResponse item = service.getReviewQueue(PageRequest.of(0, 200)).getContent().stream()
                .filter(queueItem -> transaction.getId().equals(queueItem.getTransactionId()))
                .findFirst()
                .orElseThrow();

        assertThat(item.getTimeInHold())
                .as("30 hours of hold, independent of the JVM timezone")
                .isEqualTo("1 days, 6 hours");
        // 30h > 24h contributes 20, an amount above 5000 contributes 15, and no violation adds 0.
        assertThat(item.getPriorityScore()).isEqualTo(35);
        assertThat(item.isHighPriority()).isFalse();
    }

}
