package com.aegisx.policy.service;

import com.aegisx.policy.dto.PolicyViolationResponse;
import com.aegisx.policy.dto.ViolationAnalysisResponse;
import com.aegisx.policy.dto.ViolationReportRequest;
import com.aegisx.policy.entity.PolicyEvaluation;
import com.aegisx.policy.entity.PolicyViolation;
import com.aegisx.policy.entity.ViolationSeverity;
import com.aegisx.policy.repository.PolicyViolationRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for PolicyViolationService.
 * Tests violation storage, analysis, and reporting functionality.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PolicyViolationService Tests")
class PolicyViolationServiceTest {

    @Mock
    private PolicyViolationRepository policyViolationRepository;

    @InjectMocks
    private PolicyViolationService policyViolationService;

    private Transaction testTransaction;
    private PolicyEvaluation testEvaluation;
    private PolicyViolation testViolation;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        
        // Create test transaction
        testTransaction = new Transaction();
        testTransaction.setId(UUID.randomUUID());
        testTransaction.setReferenceNumber("TXN-001");
        testTransaction.setAmount(BigDecimal.valueOf(1000.00));
        testTransaction.applyStatus(TransactionStatus.COMPLIANCE_HOLD);
        testTransaction.setCreatedAt(java.time.Instant.now());

        // Create test policy evaluation
        testEvaluation = new PolicyEvaluation();
        testEvaluation.setId(UUID.randomUUID());
        testEvaluation.setTransaction(testTransaction);
        testEvaluation.setCreatedAt(java.time.Instant.now());

        // Create test violation
        ObjectNode context = objectMapper.createObjectNode();
        context.put("amount", "1000.00");
        context.put("threshold", "500.00");
        
        testViolation = PolicyViolation.createViolation(
                testEvaluation,
                "AMOUNT_LIMIT",
                "Transaction Amount Limit",
                ViolationSeverity.HIGH,
                "Transaction amount exceeds daily limit",
                context
        );
        testViolation.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("Should create and save policy violation")
    void shouldCreateAndSavePolicyViolation() {
        // Given
        ObjectNode context = objectMapper.createObjectNode();
        context.put("field", "value");
        
        when(policyViolationRepository.save(any(PolicyViolation.class))).thenReturn(testViolation);

        // When
        PolicyViolation result = policyViolationService.createViolation(
                testEvaluation,
                "TEST_RULE",
                "Test Rule",
                ViolationSeverity.MEDIUM,
                "Test violation",
                context
        );

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getRuleId()).isEqualTo("AMOUNT_LIMIT");
        assertThat(result.getSeverity()).isEqualTo(ViolationSeverity.HIGH);
        verify(policyViolationRepository).save(any(PolicyViolation.class));
    }

    @Test
    @DisplayName("Should get violations for transaction")
    void shouldGetViolationsForTransaction() {
        // Given
        UUID transactionId = testTransaction.getId();
        List<PolicyViolation> violations = List.of(testViolation);
        when(policyViolationRepository.findByTransactionId(transactionId)).thenReturn(violations);

        // When
        List<PolicyViolationResponse> result = policyViolationService.getViolationsForTransaction(transactionId);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTransactionId()).isEqualTo(transactionId);
        assertThat(result.get(0).getSeverity()).isEqualTo(ViolationSeverity.HIGH);
        verify(policyViolationRepository).findByTransactionId(transactionId);
    }

    @Test
    @DisplayName("Should get violations for evaluation")
    void shouldGetViolationsForEvaluation() {
        // Given
        UUID evaluationId = testEvaluation.getId();
        List<PolicyViolation> violations = List.of(testViolation);
        when(policyViolationRepository.findByPolicyEvaluationId(evaluationId)).thenReturn(violations);

        // When
        List<PolicyViolationResponse> result = policyViolationService.getViolationsForEvaluation(evaluationId);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPolicyEvaluationId()).isEqualTo(evaluationId);
        verify(policyViolationRepository).findByPolicyEvaluationId(evaluationId);
    }

    @Test
    @DisplayName("Should analyze transaction violations")
    void shouldAnalyzeTransactionViolations() {
        // Given
        UUID transactionId = testTransaction.getId();
        List<PolicyViolation> violations = List.of(testViolation);
        when(policyViolationRepository.findByTransactionId(transactionId)).thenReturn(violations);

        // When
        ViolationAnalysisResponse result = policyViolationService.analyzeTransactionViolations(transactionId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getTransactionId()).isEqualTo(transactionId);
        assertThat(result.getTotalViolations()).isEqualTo(1);
        assertThat(result.getHighestSeverity()).isEqualTo(ViolationSeverity.HIGH);
        assertThat(result.isHighRisk()).isTrue();
    }

    @Test
    @DisplayName("Should analyze empty violations correctly")
    void shouldAnalyzeEmptyViolations() {
        // Given
        UUID transactionId = testTransaction.getId();
        when(policyViolationRepository.findByTransactionId(transactionId)).thenReturn(List.of());

        // When
        ViolationAnalysisResponse result = policyViolationService.analyzeTransactionViolations(transactionId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getTotalViolations()).isZero();
        assertThat(result.getRiskLevel()).isEqualTo("LOW");
        assertThat(result.isHighRisk()).isFalse();
        assertThat(result.getRecommendedActions()).contains("No violations detected");
    }

    @Test
    @DisplayName("Should get violations by severity with pagination")
    void shouldGetViolationsBySeverity() {
        // Given
        ViolationSeverity severity = ViolationSeverity.HIGH;
        Pageable pageable = PageRequest.of(0, 10);
        Page<PolicyViolation> violationPage = new PageImpl<>(List.of(testViolation), pageable, 1);
        
        when(policyViolationRepository.findBySeverity(eq(severity), any(PageRequest.class)))
                .thenReturn(violationPage);

        // When
        Page<PolicyViolationResponse> result = policyViolationService.getViolationsBySeverity(severity, pageable);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSeverity()).isEqualTo(severity);
        verify(policyViolationRepository).findBySeverity(eq(severity), any(PageRequest.class));
    }

    @Test
    @DisplayName("Should get violations requiring attention")
    void shouldGetViolationsRequiringAttention() {
        // Given
        List<PolicyViolation> violations = List.of(testViolation);
        when(policyViolationRepository.findViolationsRequiringAttention()).thenReturn(violations);

        // When
        List<PolicyViolationResponse> result = policyViolationService.getViolationsRequiringAttention();

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSeverity()).isIn(ViolationSeverity.CRITICAL, ViolationSeverity.HIGH);
        verify(policyViolationRepository).findViolationsRequiringAttention();
    }

    @Test
    @DisplayName("Should get violation statistics by rule")
    void shouldGetViolationStatsByRule() {
        // Given
        List<Object[]> stats = List.of(
                new Object[]{"AMOUNT_LIMIT", 5L},
                new Object[]{"COUNTRY_SANCTION", 2L}
        );
        when(policyViolationRepository.findMostFrequentViolations()).thenReturn(stats);

        // When
        Map<String, Long> result = policyViolationService.getViolationStatsByRule();

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).containsEntry("AMOUNT_LIMIT", 5L);
        assertThat(result).containsEntry("COUNTRY_SANCTION", 2L);
        verify(policyViolationRepository).findMostFrequentViolations();
    }

    @Test
    @DisplayName("Should check if transaction has critical violations")
    void shouldCheckIfTransactionHasCriticalViolations() {
        // Given
        UUID transactionId = testTransaction.getId();
        when(policyViolationRepository.hasCriticalViolations(transactionId)).thenReturn(true);

        // When
        boolean result = policyViolationService.hasCriticalViolations(transactionId);

        // Then
        assertThat(result).isTrue();
        verify(policyViolationRepository).hasCriticalViolations(transactionId);
    }

    @Test
    @DisplayName("Should get violations by rule ID")
    void shouldGetViolationsByRule() {
        // Given
        String ruleId = "AMOUNT_LIMIT";
        List<PolicyViolation> violations = List.of(testViolation);
        when(policyViolationRepository.findByRuleId(ruleId)).thenReturn(violations);

        // When
        List<PolicyViolationResponse> result = policyViolationService.getViolationsByRule(ruleId);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRuleId()).isEqualTo(ruleId);
        verify(policyViolationRepository).findByRuleId(ruleId);
    }

    @Test
    @DisplayName("Should generate violation report with valid request")
    void shouldGenerateViolationReport() {
        // Given
        LocalDateTime startDate = LocalDateTime.now().minusDays(7);
        LocalDateTime endDate = LocalDateTime.now();
        
        ViolationReportRequest request = ViolationReportRequest.builder()
                .startDate(startDate)
                .endDate(endDate)
                .reportType("SUMMARY")
                .maxResults(100)
                .build();

        List<PolicyViolation> violations = List.of(testViolation);
        when(policyViolationRepository.findBySeverityAndDateRange(null, startDate, endDate))
                .thenReturn(violations);

        // When
        PolicyViolationService.ViolationReportResponse result = 
                policyViolationService.generateViolationReport(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getReportType()).isEqualTo("SUMMARY");
        assertThat(result.getTotalViolations()).isEqualTo(1);
        assertThat(result.getStartDate()).isEqualTo(startDate);
        assertThat(result.getEndDate()).isEqualTo(endDate);
        assertThat(result.getSummary()).contains("1 violations");
    }

    @Test
    @DisplayName("Should validate report request dates")
    void shouldValidateReportRequestDates() {
        // Given
        ViolationReportRequest request = ViolationReportRequest.builder()
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().minusDays(1)) // End before start
                .build();

        // When & Then
        assertThatThrownBy(() -> policyViolationService.generateViolationReport(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Start date must be before end date");
    }

    @Test
    @DisplayName("Should get violation trends")
    void shouldGetViolationTrends() {
        // Given
        LocalDateTime startDate = LocalDateTime.now().minusDays(7);
        LocalDateTime endDate = LocalDateTime.now();
        List<PolicyViolation> violations = List.of(testViolation);
        
        when(policyViolationRepository.findBySeverityAndDateRange(null, startDate, endDate))
                .thenReturn(violations);

        // When
        Map<String, Object> result = policyViolationService.getViolationTrends(startDate, endDate);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).containsKey("dailyCounts");
        assertThat(result).containsKey("severityCounts");
        assertThat(result).containsKey("totalViolations");
        assertThat(result.get("totalViolations")).isEqualTo(1);
    }

    @Test
    @DisplayName("Should create default report request")
    void shouldCreateDefaultReportRequest() {
        // When
        ViolationReportRequest result = ViolationReportRequest.defaultReport();

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getReportType()).isEqualTo("SUMMARY");
        assertThat(result.isIncludeResolved()).isTrue();
        assertThat(result.isGroupByRule()).isTrue();
        assertThat(result.getMaxResults()).isEqualTo(1000);
        assertThat(result.getStartDate()).isBefore(result.getEndDate());
    }

    @Test
    @DisplayName("Should create critical violations report")
    void shouldCreateCriticalViolationsReport() {
        // Given
        LocalDateTime startDate = LocalDateTime.now().minusDays(7);
        LocalDateTime endDate = LocalDateTime.now();

        // When
        ViolationReportRequest result = ViolationReportRequest.criticalViolationsReport(startDate, endDate);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getSeverities()).containsExactly(ViolationSeverity.CRITICAL);
        assertThat(result.getReportType()).isEqualTo("DETAILED");
        assertThat(result.isIncludeResolved()).isFalse();
    }

    @Test
    @DisplayName("Should create rule-specific report")
    void shouldCreateRuleSpecificReport() {
        // Given
        String ruleId = "AMOUNT_LIMIT";
        LocalDateTime startDate = LocalDateTime.now().minusDays(7);
        LocalDateTime endDate = LocalDateTime.now();

        // When
        ViolationReportRequest result = ViolationReportRequest.ruleSpecificReport(ruleId, startDate, endDate);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getRuleIds()).containsExactly(ruleId);
        assertThat(result.getReportType()).isEqualTo("DETAILED");
        assertThat(result.isGroupBySeverity()).isTrue();
    }

    @Test
    @DisplayName("Should handle empty violation list in analysis")
    void shouldHandleEmptyViolationListInAnalysis() {
        // Given
        UUID transactionId = UUID.randomUUID();
        when(policyViolationRepository.findByTransactionId(transactionId)).thenReturn(List.of());

        // When
        ViolationAnalysisResponse result = policyViolationService.analyzeTransactionViolations(transactionId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getTotalViolations()).isZero();
        assertThat(result.getViolationsBySeverity()).isEmpty();
        assertThat(result.getViolationSummaries()).isEmpty();
        assertThat(result.isHasBlockingViolations()).isFalse();
    }

    @Test
    @DisplayName("Should sort violations by severity in transaction violations")
    void shouldSortViolationsBySeverityInTransactionViolations() {
        // Given
        PolicyViolation criticalViolation = PolicyViolation.createViolation(
                testEvaluation, "CRITICAL_RULE", "Critical Rule", 
                ViolationSeverity.CRITICAL, "Critical issue", null);
        PolicyViolation lowViolation = PolicyViolation.createViolation(
                testEvaluation, "LOW_RULE", "Low Rule", 
                ViolationSeverity.LOW, "Minor issue", null);
        
        UUID transactionId = testTransaction.getId();
        List<PolicyViolation> violations = List.of(lowViolation, testViolation, criticalViolation);
        when(policyViolationRepository.findByTransactionId(transactionId)).thenReturn(violations);

        // When
        List<PolicyViolationResponse> result = policyViolationService.getViolationsForTransaction(transactionId);

        // Then
        assertThat(result).hasSize(3);
        // Should be sorted by priority level (highest first)
        assertThat(result.get(0).getSeverity()).isEqualTo(ViolationSeverity.CRITICAL);
        assertThat(result.get(1).getSeverity()).isEqualTo(ViolationSeverity.HIGH);
        assertThat(result.get(2).getSeverity()).isEqualTo(ViolationSeverity.LOW);
    }
}