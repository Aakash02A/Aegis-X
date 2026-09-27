package com.aegisx.policy.dto;

import com.aegisx.policy.entity.ViolationSeverity;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Request DTO for generating violation reports.
 * Used to specify criteria for violation analysis and reporting.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ViolationReportRequest {

    @NotNull(message = "Start date is required")
    private LocalDateTime startDate;

    @NotNull(message = "End date is required")
    private LocalDateTime endDate;

    private List<String> ruleIds;
    private List<ViolationSeverity> severities;
    private String reportType;
    private boolean includeResolved;
    private boolean groupByRule;
    private boolean groupBySeverity;
    private int maxResults;

    /**
     * Validates the report request.
     */
    public void validate() {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("Start and end dates are required");
        }
        
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Start date must be before end date");
        }
        
        if (startDate.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Start date cannot be in the future");
        }
        
        if (maxResults < 0) {
            throw new IllegalArgumentException("Max results cannot be negative");
        }
    }

    /**
     * Gets safe max results value.
     */
    public int getSafeMaxResults() {
        return maxResults > 0 ? maxResults : 1000; // Default limit
    }

    /**
     * Returns true if specific rule filtering is requested.
     */
    public boolean hasRuleFilter() {
        return ruleIds != null && !ruleIds.isEmpty();
    }

    /**
     * Returns true if severity filtering is requested.
     */
    public boolean hasSeverityFilter() {
        return severities != null && !severities.isEmpty();
    }

    /**
     * Gets default report type if not specified.
     */
    public String getSafeReportType() {
        return reportType != null ? reportType : "SUMMARY";
    }

    /**
     * Creates a default report request for the last 30 days.
     */
    public static ViolationReportRequest defaultReport() {
        return ViolationReportRequest.builder()
                .startDate(LocalDateTime.now().minusDays(30))
                .endDate(LocalDateTime.now())
                .reportType("SUMMARY")
                .includeResolved(true)
                .groupByRule(true)
                .groupBySeverity(true)
                .maxResults(1000)
                .build();
    }

    /**
     * Creates a report request for critical violations only.
     */
    public static ViolationReportRequest criticalViolationsReport(LocalDateTime startDate, LocalDateTime endDate) {
        return ViolationReportRequest.builder()
                .startDate(startDate)
                .endDate(endDate)
                .severities(List.of(ViolationSeverity.CRITICAL))
                .reportType("DETAILED")
                .includeResolved(false)
                .groupByRule(true)
                .maxResults(500)
                .build();
    }

    /**
     * Creates a report request for a specific rule.
     */
    public static ViolationReportRequest ruleSpecificReport(String ruleId, LocalDateTime startDate, LocalDateTime endDate) {
        return ViolationReportRequest.builder()
                .startDate(startDate)
                .endDate(endDate)
                .ruleIds(List.of(ruleId))
                .reportType("DETAILED")
                .includeResolved(true)
                .groupBySeverity(true)
                .maxResults(500)
                .build();
    }
}