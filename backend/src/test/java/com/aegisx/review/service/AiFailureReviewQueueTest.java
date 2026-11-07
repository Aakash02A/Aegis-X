package com.aegisx.review.service;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.review.entity.ReviewDecision;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("AI failure review routing")
class AiFailureReviewQueueTest {

    @Autowired private ComplianceReviewService service;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;

    @Test
    @WithMockUser(roles = "COMPLIANCE_OFFICER")
    void aiFailureIsVisibleToReviewersButCannotBeApprovedOrRejectedFromThatState() {
        User merchant = userRepository.saveAndFlush(User.builder()
                .email("merchant-ai-failed@aegis-x.test")
                .fullName("Aegis Exports")
                .passwordHash("not-used-by-this-service")
                .role(Role.MERCHANT)
                .build());
        Transaction transaction = transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-AI-FAILED-" + UUID.randomUUID())
                .merchant(merchant)
                .status(TransactionStatus.AI_ANALYSIS_FAILED)
                .amount(new BigDecimal("6000.00"))
                .currency("INR")
                .beneficiaryName("Beneficiary")
                .purposeCode("P0102")
                .build());

        var queue = service.getReviewQueue(PageRequest.of(0, 20), "AI_FAILED");

        assertThat(queue.getContent()).extracting(item -> item.getTransactionId())
                .contains(transaction.getId());
        for (ReviewDecision decision : ReviewDecision.values()) {
            assertThatThrownBy(() -> service.submitReview(transaction.getId(), UUID.randomUUID(),
                    decision, "AI failure must remain a review signal only"))
                    .as("AI failure state cannot be directly %s", decision)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not in COMPLIANCE_HOLD state");
        }
        assertThat(transactionRepository.findById(transaction.getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.AI_ANALYSIS_FAILED);
    }
}
