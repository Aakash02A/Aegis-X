package com.aegisx.performance;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Performance Metrics Controller
 * Provides performance data for demo validation and monitoring
 */
@RestController
@RequestMapping("/api/performance")
@PreAuthorize("hasAnyRole('ADMIN', 'COMPLIANCE_OFFICER')")
public class PerformanceController {
    
    private final MeterRegistry meterRegistry;
    
    public PerformanceController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
    
    /**
     * Get current performance metrics for demo validation
     */
    @GetMapping("/metrics")
    public Map<String, Object> getPerformanceMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        // Define operations to measure
        String[] operations = {
            "authentication", "transaction_creation", "ocr_processing", 
            "ai_analysis", "policy_evaluation"
        };
        
        // Define performance thresholds (in milliseconds)
        Map<String, Long> thresholds = Map.of(
            "authentication", 2000L,
            "transaction_creation", 3000L,
            "ocr_processing", 10000L,
            "ai_analysis", 15000L,
            "policy_evaluation", 2000L
        );
        
        Map<String, Object> operationMetrics = new HashMap<>();
        
        for (String operation : operations) {
            Timer timer = meterRegistry.find("aegisx.performance")
                    .tag("operation", operation)
                    .tag("status", "success")
                    .timer();
                    
            if (timer != null && timer.count() > 0) {
                double avgDuration = timer.mean(TimeUnit.MILLISECONDS);
                double maxDuration = timer.max(TimeUnit.MILLISECONDS);
                long threshold = thresholds.get(operation);
                
                Map<String, Object> operationData = new HashMap<>();
                operationData.put("avg_duration_ms", Math.round(avgDuration * 100.0) / 100.0);
                operationData.put("max_duration_ms", Math.round(maxDuration * 100.0) / 100.0);
                operationData.put("threshold_ms", threshold);
                operationData.put("count", timer.count());
                operationData.put("meets_target", avgDuration <= threshold);
                operationData.put("performance_level", getPerformanceLevel(avgDuration, threshold));
                
                operationMetrics.put(operation, operationData);
            } else {
                // No data available for this operation
                Map<String, Object> operationData = new HashMap<>();
                operationData.put("avg_duration_ms", 0);
                operationData.put("max_duration_ms", 0);
                operationData.put("threshold_ms", thresholds.get(operation));
                operationData.put("count", 0);
                operationData.put("meets_target", true);
                operationData.put("performance_level", "NO_DATA");
                
                operationMetrics.put(operation, operationData);
            }
        }
        
        metrics.put("operations", operationMetrics);
        
        // Calculate overall performance score
        metrics.put("overall_performance", calculateOverallPerformance(operationMetrics));
        
        return metrics;
    }
    
    /**
     * Get demo readiness status based on performance
     */
    @GetMapping("/demo-readiness")
    public Map<String, Object> getDemoReadiness() {
        Map<String, Object> readiness = new HashMap<>();
        Map<String, Object> metrics = getPerformanceMetrics();
        
        @SuppressWarnings("unchecked")
        Map<String, Object> operations = (Map<String, Object>) metrics.get("operations");
        
        int totalOperations = operations.size();
        int meetingTargets = 0;
        
        for (Map.Entry<String, Object> entry : operations.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> operationData = (Map<String, Object>) entry.getValue();
            if ((Boolean) operationData.get("meets_target")) {
                meetingTargets++;
            }
        }
        
        double successRate = totalOperations > 0 ? (double) meetingTargets / totalOperations : 1.0;
        
        String demoStatus;
        String recommendation;
        
        if (successRate >= 0.9) {
            demoStatus = "READY";
            recommendation = "All performance targets met - system ready for demo!";
        } else if (successRate >= 0.8) {
            demoStatus = "MOSTLY_READY";
            recommendation = "Most targets met - monitor performance during demo";
        } else if (successRate >= 0.7) {
            demoStatus = "NEEDS_ATTENTION";
            recommendation = "Some performance issues detected - review before demo";
        } else {
            demoStatus = "NOT_READY";
            recommendation = "Significant performance issues - optimize before demo";
        }
        
        readiness.put("demo_status", demoStatus);
        readiness.put("success_rate", Math.round(successRate * 100 * 100.0) / 100.0);
        readiness.put("targets_met", meetingTargets);
        readiness.put("total_targets", totalOperations);
        readiness.put("recommendation", recommendation);
        readiness.put("timestamp", System.currentTimeMillis());
        
        return readiness;
    }
    
    /**
     * Reset performance metrics (useful for clean demo testing)
     */
    @GetMapping("/reset")
    public Map<String, String> resetMetrics() {
        // Clear performance timers
        String[] operations = {
            "authentication", "transaction_creation", "ocr_processing", 
            "ai_analysis", "policy_evaluation"
        };
        
        for (String operation : operations) {
            Timer timer = meterRegistry.find("aegisx.performance")
                    .tag("operation", operation)
                    .timer();
            if (timer != null) {
                meterRegistry.remove(timer);
            }
        }
        
        return Map.of("status", "Performance metrics reset successfully");
    }
    
    /**
     * Calculate overall performance grade
     */
    private Map<String, Object> calculateOverallPerformance(Map<String, Object> operations) {
        int totalOps = operations.size();
        int excellentOps = 0;
        int goodOps = 0;
        int acceptableOps = 0;
        int poorOps = 0;
        int noDataOps = 0;
        
        for (Map.Entry<String, Object> entry : operations.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> operationData = (Map<String, Object>) entry.getValue();
            String level = (String) operationData.get("performance_level");
            
            switch (level) {
                case "EXCELLENT": excellentOps++; break;
                case "GOOD": goodOps++; break;
                case "ACCEPTABLE": acceptableOps++; break;
                case "POOR": poorOps++; break;
                case "NO_DATA": noDataOps++; break;
            }
        }
        
        Map<String, Object> overall = new HashMap<>();
        overall.put("excellent_operations", excellentOps);
        overall.put("good_operations", goodOps);
        overall.put("acceptable_operations", acceptableOps);
        overall.put("poor_operations", poorOps);
        overall.put("no_data_operations", noDataOps);
        
        // Calculate grade
        String grade;
        if (poorOps == 0 && excellentOps >= totalOps * 0.7) {
            grade = "A";
        } else if (poorOps <= 1 && (excellentOps + goodOps) >= totalOps * 0.8) {
            grade = "B";
        } else if (poorOps <= 2 && (excellentOps + goodOps + acceptableOps) >= totalOps * 0.8) {
            grade = "C";
        } else {
            grade = "D";
        }
        
        overall.put("grade", grade);
        overall.put("demo_ready", poorOps == 0 && grade.equals("A"));
        
        return overall;
    }
    
    /**
     * Determine performance level based on actual vs threshold
     */
    private String getPerformanceLevel(double actualMs, long thresholdMs) {
        if (actualMs <= thresholdMs * 0.5) {
            return "EXCELLENT";
        } else if (actualMs <= thresholdMs * 0.8) {
            return "GOOD";
        } else if (actualMs <= thresholdMs) {
            return "ACCEPTABLE";
        } else {
            return "POOR";
        }
    }
}