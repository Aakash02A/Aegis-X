package com.aegisx.policy.service;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.policy.dto.PolicyViolationResponse;
import com.aegisx.policy.dto.ViolationAnalysisResponse;
import com.aegisx.policy.dto.ViolationReportRequest;
import com.aegisx.policy.entity.PolicyEvaluation;
import com.aegisx.policy.entity.PolicyVerdict;
import com.aegisx.policy.entity.PolicyViolation;
import com.aegisx.policy.entity.ViolationSeverity;
import com.aegisx.policy.repository.PolicyEvaluationRepository;
import com.aegisx.policy.repository.PolicyViolationRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Policy violation service integration")
class PolicyViolationServiceTest {

    @Autowired private PolicyViolationService service;
    @Autowired private PolicyViolationRepository violationRepository;
    @Autowired private PolicyEvaluationRepository evaluationRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;

    private Transaction transaction;
    private PolicyEvaluation evaluation;

    @BeforeEach
    void setUp() {
        User merchant = userRepository.saveAndFlush(User.builder()
                .email("violation-" + UUID.randomUUID() + "@aegis-x.test")
                .fullName("Violation Test Merchant")
                .passwordHash("not-used-by-this-service")
                .role(Role.MERCHANT)
                .build());
        transaction = transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-VIOLATION-" + UUID.randomUUID())
                .merchant(merchant)
                .amount(new BigDecimal("1000.00"))
                .currency("USD")
                .beneficiaryName("Test Beneficiary")
                .purposeCode("P0101")
                .status(TransactionStatus.COMPLIANCE_HOLD)
                .build());
        evaluation = evaluationRepository.saveAndFlush(PolicyEvaluation.createEvaluation(
                transaction, null, PolicyVerdict.HOLD, 1, 0, 1));
    }

    @Test
    @DisplayName("creates and persists a policy violation")
    void shouldCreateAndSavePolicyViolation() {
        var context = objectMapper.createObjectNode().put("field", "value");
        PolicyViolation result = service.createViolation(
                evaluation, "TEST_RULE", "Test Rule", ViolationSeverity.MEDIUM, "Test violation", context);

        assertThat(result.getId()).isNotNull();
        assertThat(violationRepository.findById(result.getId())).get()
                .extracting(PolicyViolation::getRuleId)
                .isEqualTo("TEST_RULE");
    }

    @Test
    @DisplayName("reads transaction/evaluation violations and returns severity-sorted analysis")
    void readsAndAnalyzesPersistedViolations() {
        saveViolation(evaluation, "LOW_RULE", ViolationSeverity.LOW);
        saveViolation(evaluation, "HIGH_RULE", ViolationSeverity.HIGH);
        List<PolicyViolationResponse> transactionRows = service.getViolationsForTransaction(transaction.getId());
        List<PolicyViolationResponse> evaluationRows = service.getViolationsForEvaluation(evaluation.getId());

        assertThat(transactionRows).extracting(PolicyViolationResponse::getSeverity)
                .containsExactly(ViolationSeverity.HIGH, ViolationSeverity.LOW);
        assertThat(evaluationRows).hasSize(2);
        assertThat(service.analyzeTransactionViolations(transaction.getId()).getHighestSeverity())
                .isEqualTo(ViolationSeverity.HIGH);
        assertThat(service.analyzeTransactionViolations(UUID.randomUUID()).getRiskLevel()).isEqualTo("LOW");
    }

    @Test
    @DisplayName("queries severity pages, attention queue, rule aggregates and critical status")
    void repositoryQueriesReflectPersistedViolations() {
        saveViolation(evaluation, "AMOUNT_LIMIT", ViolationSeverity.CRITICAL);
        saveViolation(evaluation, "AMOUNT_LIMIT", ViolationSeverity.HIGH);
        saveViolation(evaluation, "COUNTRY_SANCTION", ViolationSeverity.LOW);

        Page<PolicyViolationResponse> page =
                service.getViolationsBySeverity(ViolationSeverity.HIGH, PageRequest.of(0, 10));
        List<PolicyViolationResponse> attention = service.getViolationsRequiringAttention();
        Map<String, Long> stats = service.getViolationStatsByRule();

        assertThat(page.getContent()).hasSize(1);
        assertThat(attention).hasSize(2);
        assertThat(stats).containsEntry("AMOUNT_LIMIT", 2L).containsEntry("COUNTRY_SANCTION", 1L);
        assertThat(service.hasCriticalViolations(transaction.getId())).isTrue();
        assertThat(service.getViolationsByRule("AMOUNT_LIMIT")).hasSize(2);
    }

    @Test
    @DisplayName("builds reports and trends from persisted data and validates report dates")
    void reportsAndTrendsUsePersistedData() {
        saveViolation(evaluation, "REPORT_RULE", ViolationSeverity.HIGH);
        LocalDateTime start = LocalDateTime.now().minusDays(7);
        LocalDateTime end = LocalDateTime.now().plusMinutes(1);
        ViolationReportRequest request = ViolationReportRequest.builder()
                .startDate(start).endDate(end).reportType("SUMMARY").maxResults(100).build();

        PolicyViolationService.ViolationReportResponse report = service.generateViolationReport(request);
        Map<String, Object> trends = service.getViolationTrends(start, end);

        assertThat(report.getReportType()).isEqualTo("SUMMARY");
        assertThat(report.getTotalViolations()).isEqualTo(1);
        assertThat(report.getSummary()).contains("1 violations");
        assertThat(trends).containsKeys("dailyCounts", "severityCounts", "totalViolations")
                .containsEntry("totalViolations", 1);
        assertThatThrownBy(() -> service.generateViolationReport(ViolationReportRequest.builder()
                .startDate(end).endDate(start).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Start date must be before end date");
    }

    @Test
    @DisplayName("report request factories preserve filters and defaults")
    void requestFactoriesPreserveCriteria() {
        ViolationReportRequest defaults = ViolationReportRequest.defaultReport();
        LocalDateTime start = LocalDateTime.now().minusDays(7);
        LocalDateTime end = LocalDateTime.now();
        ViolationReportRequest critical = ViolationReportRequest.criticalViolationsReport(start, end);
        ViolationReportRequest rule = ViolationReportRequest.ruleSpecificReport("AMOUNT_LIMIT", start, end);

        assertThat(defaults.getReportType()).isEqualTo("SUMMARY");
        assertThat(defaults.isIncludeResolved()).isTrue();
        assertThat(defaults.isGroupByRule()).isTrue();
        assertThat(defaults.getMaxResults()).isEqualTo(1000);
        assertThat(defaults.getStartDate()).isBefore(defaults.getEndDate());
        assertThat(critical.getSeverities()).containsExactly(ViolationSeverity.CRITICAL);
        assertThat(rule.getRuleIds()).containsExactly("AMOUNT_LIMIT");
    }

    private PolicyViolation saveViolation(PolicyEvaluation owner, String ruleId, ViolationSeverity severity) {
        return violationRepository.saveAndFlush(PolicyViolation.createViolation(
                owner, ruleId, ruleId, severity, "Test violation for " + ruleId,
                objectMapper.createObjectNode().put("rule", ruleId)));
    }
}
