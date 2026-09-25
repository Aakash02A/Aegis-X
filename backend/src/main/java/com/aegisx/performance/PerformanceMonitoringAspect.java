package com.aegisx.performance;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Performance Monitoring Aspect
 * Automatically measures execution time of critical operations for demo readiness validation
 */
@Aspect
@Component
public class PerformanceMonitoringAspect {
    
    private static final Logger logger = LoggerFactory.getLogger(PerformanceMonitoringAspect.class);
    private final MeterRegistry meterRegistry;
    
    // Performance thresholds for demo validation (in milliseconds)
    private static final long AUTH_THRESHOLD = 2000;      // 2 seconds
    private static final long TRANSACTION_THRESHOLD = 3000; // 3 seconds
    private static final long OCR_THRESHOLD = 10000;      // 10 seconds
    private static final long AI_THRESHOLD = 15000;       // 15 seconds
    private static final long POLICY_THRESHOLD = 2000;    // 2 seconds
    
    public PerformanceMonitoringAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
    
    /**
     * Monitor authentication operations
     */
    @Around("execution(* com.aegisx.auth.service.UserService.authenticateUser(..))")
    public Object monitorAuthentication(ProceedingJoinPoint joinPoint) throws Throwable {
        return measureOperation(joinPoint, "authentication", AUTH_THRESHOLD);
    }
    
    /**
     * Monitor transaction creation
     */
    @Around("execution(* com.aegisx.transaction.service.TransactionService.createTransaction(..))")
    public Object monitorTransactionCreation(ProceedingJoinPoint joinPoint) throws Throwable {
        return measureOperation(joinPoint, "transaction_creation", TRANSACTION_THRESHOLD);
    }
    
    /**
     * Monitor OCR processing
     */
    @Around("execution(* com.aegisx.document.service.OcrService.processDocument(..))")
    public Object monitorOcrProcessing(ProceedingJoinPoint joinPoint) throws Throwable {
        return measureOperation(joinPoint, "ocr_processing", OCR_THRESHOLD);
    }
    
    /**
     * Monitor AI analysis
     */
    @Around("execution(* com.aegisx.ai.service.AiAnalysisService.analyzeDocument(..))")
    public Object monitorAiAnalysis(ProceedingJoinPoint joinPoint) throws Throwable {
        return measureOperation(joinPoint, "ai_analysis", AI_THRESHOLD);
    }
    
    /**
     * Monitor policy evaluation
     */
    @Around("execution(* com.aegisx.policy.service.PolicyEvaluationService.evaluateTransaction(..))")
    public Object monitorPolicyEvaluation(ProceedingJoinPoint joinPoint) throws Throwable {
        return measureOperation(joinPoint, "policy_evaluation", POLICY_THRESHOLD);
    }
    
    /**
     * Generic operation measurement
     */
    private Object measureOperation(ProceedingJoinPoint joinPoint, String operationName, long thresholdMs) throws Throwable {
        Timer.Sample sample = Timer.start(meterRegistry);
        long startTime = System.currentTimeMillis();
        
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            
            // Record metrics
            sample.stop(Timer.builder("aegisx.performance")
                    .tag("operation", operationName)
                    .tag("status", "success")
                    .register(meterRegistry));
            
            // Log performance information
            String performanceLevel = getPerformanceLevel(duration, thresholdMs);
            logger.info("Performance [{}]: {}ms {} (threshold: {}ms)", 
                    operationName, duration, performanceLevel, thresholdMs);
            
            // Alert if performance is concerning for demo
            if (duration > thresholdMs) {
                logger.warn("DEMO ALERT: {} took {}ms, exceeds threshold of {}ms", 
                        operationName, duration, thresholdMs);
            }
            
            return result;
            
        } catch (Throwable throwable) {
            long duration = System.currentTimeMillis() - startTime;
            
            // Record failure metrics
            sample.stop(Timer.builder("aegisx.performance")
                    .tag("operation", operationName)
                    .tag("status", "failure")
                    .register(meterRegistry));
            
            logger.error("Performance [{}]: {}ms FAILED - {}", 
                    operationName, duration, throwable.getMessage());
            
            throw throwable;
        }
    }
    
    /**
     * Determine performance level for logging
     */
    private String getPerformanceLevel(long actualMs, long thresholdMs) {
        if (actualMs <= thresholdMs * 0.5) {
            return "🟢 EXCELLENT";
        } else if (actualMs <= thresholdMs * 0.8) {
            return "🟡 GOOD";
        } else if (actualMs <= thresholdMs) {
            return "🟠 ACCEPTABLE";
        } else {
            return "🔴 POOR";
        }
    }
}