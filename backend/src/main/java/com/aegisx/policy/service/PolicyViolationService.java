package com.aegisx.policy.service;

import com.aegisx.policy.dto.PolicyViolationResponse;
import com.aegisx.policy.dto.ViolationAnalysisResponse;
import com.aegisx.policy.dto.ViolationReportRequest;
import com.aegisx.policy.entity.PolicyEvaluation;
import com.aegisx.policy.entity.PolicyViolation;
import com.aegisx.policy.entity.ViolationSeverity;
import com.aegisx.policy.repository.PolicyViolationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for managing policy violations.
 * Implements violation storage, analysis, and reporting according to SRS Section 8.3.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PolicyViolationService {

    private final PolicyViolationRepository policyViolationRepository;

    /**
     * Creates and stores a policy violation.
     */
    public PolicyViolation createViolation(PolicyEvaluation evaluation, 
                                          String ruleId, 
                                          String ruleName,
                                          ViolationSeverity severity, 
                                          String description,
                                          JsonNode context) {
        log.debug("Creating policy violation for rule: {}, severity: {}", ruleId, severity);
        
        PolicyViolation violation = PolicyViolation.createViolation(
                evaluation, ruleId, ruleName, severity, description, context);
        
        PolicyViolation saved = policyViolationRepository.save(violation);
        
        log.info("Policy violation created: {} for transaction: {}", 
                saved.getId(), evaluation.getTransaction().getId());
        
        return saved;
    }

    /**
     * Gets all violations for a transaction.
     */
    @Transactional(readOnly = true)
    public List<PolicyViolationResponse> getViolationsForTransaction(UUID transactionId) {
        log.debug("Getting violations for transaction: {}", transactionId);
        
        List<PolicyViolation> violations = policyViolationRepository.findByTransactionId(transactionId);
        
        return violations.stream()
                .map(PolicyViolationResponse::fromEntity)
                .sorted((v1, v2) -> v2.getSeverity().getPriorityLevel() - v1.getSeverity().getPriorityLevel())
                .toList();
    }

    /**
     * Gets violations for a specific policy evaluation.
     */
    @Transactional(readOnly = true)
    public List<PolicyViolationResponse> getViolationsForEvaluation(UUID evaluationId) {
        log.debug("Getting violations for evaluation: {}", evaluationId);
        
        List<PolicyViolation> violations = policyViolationRepository.findByPolicyEvaluationId(evaluationId);
        
        return violations.stream()
                .map(PolicyViolationResponse::fromEntity)
                .toList();
    }

    /**
     * Analyzes violations for a transaction and provides risk assessment.
     */
    @Transactional(readOnly = true)
    public ViolationAnalysisResponse analyzeTransactionViolations(UUID transactionId) {
        log.debug("Analyzing violations for transaction: {}", transactionId);
        
        List<PolicyViolationResponse> violations = getViolationsForTransaction(transactionId);
        
        if (violations.isEmpty()) {
            log.debug("No violations found for transaction: {}", transactionId);
        }
        
        // Get transaction reference from first violation or lookup
        String transactionReference = violations.isEmpty() ? 
                "Unknown" : violations.get(0).getTransactionReference();
        
        return ViolationAnalysisResponse.create(transactionId, transactionReference, violations);
    }

    /**
     * Gets violations by severity level with pagination.
     */
    @Transactional(readOnly = true)
    public Page<PolicyViolationResponse> getViolationsBySeverity(ViolationSeverity severity, Pageable pageable) {
        log.debug("Getting violations by severity: {}", severity);
        
        Page<PolicyViolation> violationPage = policyViolationRepository.findBySeverity(severity, 
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), 
                        Sort.by(Sort.Direction.DESC, "createdAt")));
        
        return violationPage.map(PolicyViolationResponse::fromEntity);
    }

    /**
     * Gets violations requiring immediate attention (Critical and High severity).
     */
    @Transactional(readOnly = true)
    public List<PolicyViolationResponse> getViolationsRequiringAttention() {
        log.debug("Getting violations requiring attention");
        
        List<PolicyViolation> violations = policyViolationRepository.findViolationsRequiringAttention();
        
        return violations.stream()
                .map(PolicyViolationResponse::fromEntity)
                .toList();
    }

    /**
     * Gets violation statistics by rule.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> getViolationStatsByRule() {
        log.debug("Getting violation statistics by rule");
        
        List<Object[]> results = policyViolationRepository.findMostFrequentViolations();
        
        return results.stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],  // Rule ID
                        row -> (Long) row[1]     // Count
                ));
    }

    /**
     * Generates violation report based on criteria.
     */
    @Transactional(readOnly = true)
    public ViolationReportResponse generateViolationReport(ViolationReportRequest request) {
        log.info("Generating violation report from {} to {}", request.getStartDate(), request.getEndDate());
        
        request.validate();
        
        // Get violations based on criteria
        List<PolicyViolation> violations = findViolationsByCriteria(request);
        
        List<PolicyViolationResponse> violationResponses = violations.stream()
                .map(PolicyViolationResponse::fromEntity)
                .toList();
        
        return ViolationReportResponse.builder()
                .reportType(request.getSafeReportType())
                .generatedAt(LocalDateTime.now())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .totalViolations(violations.size())
                .violations(violationResponses)
                .violationsByRule(groupViolationsByRule(violationResponses))
                .violationsBySeverity(groupViolationsBySeverity(violationResponses))
                .summary(generateReportSummary(violationResponses, request))
                .build();
    }

    /**
     * Checks if a transaction has critical violations.
     */
    @Transactional(readOnly = true)
    public boolean hasCriticalViolations(UUID transactionId) {
        return policyViolationRepository.hasCriticalViolations(transactionId);
    }

    /**
     * Gets violations by rule ID.
     */
    @Transactional(readOnly = true)
    public List<PolicyViolationResponse> getViolationsByRule(String ruleId) {
        log.debug("Getting violations for rule: {}", ruleId);
        
        List<PolicyViolation> violations = policyViolationRepository.findByRuleId(ruleId);
        
        return violations.stream()
                .map(PolicyViolationResponse::fromEntity)
                .sorted((v1, v2) -> v2.getCreatedAt().compareTo(v1.getCreatedAt()))
                .toList();
    }

    /**
     * Gets violation trends over time.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getViolationTrends(LocalDateTime startDate, LocalDateTime endDate) {
        log.debug("Getting violation trends from {} to {}", startDate, endDate);
        
        List<PolicyViolation> violations = policyViolationRepository.findBySeverityAndDateRange(
                null, startDate.atZone(java.time.ZoneOffset.UTC).toInstant(),
                endDate.atZone(java.time.ZoneOffset.UTC).toInstant());
        
        // Group by date and severity for trend analysis
        Map<String, Long> dailyCounts = violations.stream()
                .collect(Collectors.groupingBy(
                        v -> v.getCreatedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString(),
                        Collectors.counting()
                ));
        
        Map<ViolationSeverity, Long> severityCounts = violations.stream()
                .collect(Collectors.groupingBy(
                        PolicyViolation::getSeverity,
                        Collectors.counting()
                ));
        
        return Map.of(
                "dailyCounts", dailyCounts,
                "severityCounts", severityCounts,
                "totalViolations", violations.size(),
                "period", Map.of("start", startDate, "end", endDate)
        );
    }

    /**
     * Finds violations based on report criteria.
     */
    private List<PolicyViolation> findViolationsByCriteria(ViolationReportRequest request) {
        List<PolicyViolation> violations;
        
        if (request.hasSeverityFilter() && request.getSeverities().size() == 1) {
            violations = policyViolationRepository.findBySeverityAndDateRange(
                    request.getSeverities().get(0),
                    request.getStartDate().atZone(java.time.ZoneOffset.UTC).toInstant(),
                    request.getEndDate().atZone(java.time.ZoneOffset.UTC).toInstant());
        } else {
            // For now, get all violations in date range and filter in memory
            // In production, this would use a more sophisticated query
            violations = policyViolationRepository.findBySeverityAndDateRange(
                    null, request.getStartDate().atZone(java.time.ZoneOffset.UTC).toInstant(),
                    request.getEndDate().atZone(java.time.ZoneOffset.UTC).toInstant());
            
            if (request.hasSeverityFilter()) {
                violations = violations.stream()
                        .filter(v -> request.getSeverities().contains(v.getSeverity()))
                        .toList();
            }
            
            if (request.hasRuleFilter()) {
                violations = violations.stream()
                        .filter(v -> request.getRuleIds().contains(v.getRuleId()))
                        .toList();
            }
        }
        
        // Apply max results limit
        if (violations.size() > request.getSafeMaxResults()) {
            violations = violations.subList(0, request.getSafeMaxResults());
        }
        
        return violations;
    }

    /**
     * Groups violations by rule for reporting.
     */
    private Map<String, Object> groupViolationsByRule(List<PolicyViolationResponse> violations) {
        return violations.stream()
                .collect(Collectors.groupingBy(
                        v -> v.getRuleId() + " - " + v.getRuleName(),
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> Map.of(
                                        "count", list.size(),
                                        "highestSeverity", list.stream()
                                                .map(PolicyViolationResponse::getSeverity)
                                                .max((s1, s2) -> Integer.compare(s1.getPriorityLevel(), s2.getPriorityLevel()))
                                                .orElse(ViolationSeverity.LOW)
                                )
                        )
                ));
    }

    /**
     * Groups violations by severity for reporting.
     */
    private Map<ViolationSeverity, Long> groupViolationsBySeverity(List<PolicyViolationResponse> violations) {
        return violations.stream()
                .collect(Collectors.groupingBy(
                        PolicyViolationResponse::getSeverity,
                        Collectors.counting()
                ));
    }

    /**
     * Generates report summary.
     */
    private String generateReportSummary(List<PolicyViolationResponse> violations, ViolationReportRequest request) {
        if (violations.isEmpty()) {
            return "No violations found in the specified period.";
        }
        
        long critical = violations.stream().filter(v -> v.getSeverity() == ViolationSeverity.CRITICAL).count();
        long high = violations.stream().filter(v -> v.getSeverity() == ViolationSeverity.HIGH).count();
        
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Found %d violations in the specified period. ", violations.size()));
        
        if (critical > 0) {
            summary.append(String.format("%d critical violations require immediate attention. ", critical));
        }
        
        if (high > 0) {
            summary.append(String.format("%d high-priority violations need review. ", high));
        }
        
        if (critical == 0 && high == 0) {
            summary.append("No critical or high-priority violations detected.");
        }
        
        return summary.toString().trim();
    }

    /**
     * Violation report response DTO.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ViolationReportResponse {
        private String reportType;
        private LocalDateTime generatedAt;
        private LocalDateTime startDate;
        private LocalDateTime endDate;
        private int totalViolations;
        private List<PolicyViolationResponse> violations;
        private Map<String, Object> violationsByRule;
        private Map<ViolationSeverity, Long> violationsBySeverity;
        private String summary;
    }
}
