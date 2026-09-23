package com.aegisx.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Business metrics service for tracking key operational metrics.
 * 
 * This service uses Micrometer (Spring Boot native metrics library) to track
 * business KPIs that are important for operations and compliance monitoring.
 * 
 * Metrics are exposed via:
 * - GET /actuator/metrics (list all metrics)
 * - GET /actuator/metrics/{name} (query specific metric)
 * - GET /actuator/prometheus (Prometheus format for Grafana, ELK, etc.)
 * 
 * Examples of metrics tracked:
 * - transactions_created_total: total transactions initiated
 * - ocr_completed_total: documents successfully extracted
 * - ai_analyses_completed_total: regulatory analyses performed
 * - policy_approved_total: transactions approved by policy engine
 * - policy_hold_total: transactions placed on compliance hold
 * - whatsapp_messages_sent_total: document requests sent
 * 
 * Design Pattern: Micrometer Facade
 * Rationale: Micrometer is the Spring Boot standard for metrics.
 *           Provides vendor-neutral metric collection.
 *           Automatic export to Prometheus, Grafana, CloudWatch, etc.
 *           No manual registry management required.
 * 
 * Usage Examples in Services:
 * 
 * // Record transaction creation
 * businessMetricsService.recordTransactionCreated();
 * 
 * // Record OCR completion
 * businessMetricsService.recordOCRCompleted();
 * 
 * // Record transaction approval
 * businessMetricsService.recordPolicyApproved();
 * 
 * // Record compliance hold
 * businessMetricsService.recordPolicyHold("HIGH_VELOCITY_DETECTED");
 * 
 * // Record timed operation
 * businessMetricsService.recordOCRProcessingTime(durationMillis);
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessMetricsService {

    private final MeterRegistry meterRegistry;

    // ==================== Transaction Metrics ====================

    /**
     * Counter for transactions created.
     * Incremented when a merchant creates a new transaction.
     */
    private Counter transactionsCreatedCounter;

    /**
     * Counter for transactions completed successfully.
     * Incremented when transaction reaches APPROVED state.
     */
    private Counter transactionsCompletedCounter;

    /**
     * Counter for transactions failed.
     * Incremented when transaction enters COMPLIANCE_HOLD or similar error states.
     */
    private Counter transactionsFailedCounter;

    // ==================== OCR Metrics ====================

    /**
     * Counter for OCR processing completion.
     * Incremented when document text extraction succeeds.
     */
    private Counter ocrCompletedCounter;

    /**
     * Counter for OCR processing failures.
     * Incremented when text extraction fails (unsupported format, corruption, etc.).
     */
    private Counter ocrFailedCounter;

    /**
     * Timer for OCR processing duration.
     * Tracks how long document extraction takes.
     */
    private Timer ocrProcessingTimer;

    // ==================== AI Analysis Metrics ====================

    /**
     * Counter for AI analyses completed.
     * Incremented when regulatory analysis completes.
     */
    private Counter aiAnalysesCompletedCounter;

    /**
     * Counter for AI analyses failed.
     * Incremented when regulatory analysis times out or errors.
     */
    private Counter aiAnalysesFailedCounter;

    /**
     * Timer for AI analysis duration.
     * Tracks how long regulatory reasoning takes.
     */
    private Timer aiAnalysisTimer;

    // ==================== Policy Engine Metrics ====================

    /**
     * Counter for transactions approved by policy engine.
     * Incremented when policy evaluation results in APPROVED state.
     */
    private Counter policyApprovedCounter;

    /**
     * Counter for transactions placed on compliance hold.
     * Incremented when policy evaluation triggers hold (review needed).
     */
    private Counter policyHoldCounter;

    /**
     * Counter for policy rule evaluations.
     * Incremented for each rule evaluated during policy decision.
     */
    private Counter policyRulesEvaluatedCounter;

    /**
     * Timer for policy evaluation duration.
     * Tracks how long compliance decision takes.
     */
    private Timer policyEvaluationTimer;

    // ==================== WhatsApp Metrics ====================

    /**
     * Counter for WhatsApp messages sent.
     * Incremented when document request successfully sent to merchant.
     */
    private Counter whatsappMessagesSentCounter;

    /**
     * Counter for WhatsApp messages delivered.
     * Incremented when Meta confirms delivery receipt.
     */
    private Counter whatsappMessagesDeliveredCounter;

    /**
     * Counter for WhatsApp media received.
     * Incremented when merchant uploads document via WhatsApp.
     */
    private Counter whatsappMediaReceivedCounter;

    // ==================== Webhook Metrics ====================

    /**
     * Counter for webhook events received.
     * Incremented for each incoming webhook from external services.
     */
    private Counter webhookEventsReceivedCounter;

    /**
     * Counter for webhook events processed successfully.
     * Incremented when webhook processing completes without error.
     */
    private Counter webhookEventsProcessedCounter;

    /**
     * Counter for webhook processing failures.
     * Incremented when webhook handler throws exception.
     */
    private Counter webhookEventsFailedCounter;

    /**
     * Lazy initialization of metrics on first use.
     * Prevents creating unused metrics.
     */
    private void initializeMetricsIfNeeded() {
        if (transactionsCreatedCounter == null) {
            initializeAllMetrics();
        }
    }

    /**
     * Initialize all metrics with Micrometer.
     * Called once on first metric access.
     */
    private void initializeAllMetrics() {
        // Transaction metrics
        transactionsCreatedCounter = Counter.builder("transactions_created_total")
            .description("Total number of transactions created")
            .register(meterRegistry);

        transactionsCompletedCounter = Counter.builder("transactions_completed_total")
            .description("Total number of transactions completed successfully")
            .register(meterRegistry);

        transactionsFailedCounter = Counter.builder("transactions_failed_total")
            .description("Total number of transactions that failed or were put on hold")
            .register(meterRegistry);

        // OCR metrics
        ocrCompletedCounter = Counter.builder("ocr_completed_total")
            .description("Total number of documents successfully extracted via OCR")
            .register(meterRegistry);

        ocrFailedCounter = Counter.builder("ocr_failed_total")
            .description("Total number of failed OCR extraction attempts")
            .register(meterRegistry);

        ocrProcessingTimer = Timer.builder("ocr_processing_duration")
            .description("Time taken to process documents via OCR")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);

        // AI analysis metrics
        aiAnalysesCompletedCounter = Counter.builder("ai_analyses_completed_total")
            .description("Total number of regulatory analyses completed")
            .register(meterRegistry);

        aiAnalysesFailedCounter = Counter.builder("ai_analyses_failed_total")
            .description("Total number of failed AI analysis attempts")
            .register(meterRegistry);

        aiAnalysisTimer = Timer.builder("ai_analysis_duration")
            .description("Time taken for regulatory analysis by Spring AI")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);

        // Policy engine metrics
        policyApprovedCounter = Counter.builder("policy_approved_total")
            .description("Total transactions approved by policy engine")
            .register(meterRegistry);

        policyHoldCounter = Counter.builder("policy_hold_total")
            .description("Total transactions placed on compliance hold")
            .register(meterRegistry);

        policyRulesEvaluatedCounter = Counter.builder("policy_rules_evaluated_total")
            .description("Total policy rules evaluated")
            .register(meterRegistry);

        policyEvaluationTimer = Timer.builder("policy_evaluation_duration")
            .description("Time taken for policy evaluation")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);

        // WhatsApp metrics
        whatsappMessagesSentCounter = Counter.builder("whatsapp_messages_sent_total")
            .description("Total WhatsApp messages sent (document requests)")
            .register(meterRegistry);

        whatsappMessagesDeliveredCounter = Counter.builder("whatsapp_messages_delivered_total")
            .description("Total WhatsApp messages confirmed delivered")
            .register(meterRegistry);

        whatsappMediaReceivedCounter = Counter.builder("whatsapp_media_received_total")
            .description("Total documents received via WhatsApp")
            .register(meterRegistry);

        // Webhook metrics
        webhookEventsReceivedCounter = Counter.builder("webhook_events_received_total")
            .description("Total webhook events received from external services")
            .register(meterRegistry);

        webhookEventsProcessedCounter = Counter.builder("webhook_events_processed_total")
            .description("Total webhook events successfully processed")
            .register(meterRegistry);

        webhookEventsFailedCounter = Counter.builder("webhook_events_failed_total")
            .description("Total webhook events that failed processing")
            .register(meterRegistry);
    }

    // ==================== Transaction Metrics Methods ====================

    /**
     * Record a transaction creation.
     */
    public void recordTransactionCreated() {
        initializeMetricsIfNeeded();
        transactionsCreatedCounter.increment();
        log.debug("Metrics: Transaction created");
    }

    /**
     * Record a transaction completion.
     */
    public void recordTransactionCompleted() {
        initializeMetricsIfNeeded();
        transactionsCompletedCounter.increment();
        log.debug("Metrics: Transaction completed");
    }

    /**
     * Record a transaction failure or hold.
     */
    public void recordTransactionFailed() {
        initializeMetricsIfNeeded();
        transactionsFailedCounter.increment();
        log.debug("Metrics: Transaction failed");
    }

    // ==================== OCR Metrics Methods ====================

    /**
     * Record successful OCR completion.
     */
    public void recordOCRCompleted() {
        initializeMetricsIfNeeded();
        ocrCompletedCounter.increment();
        log.debug("Metrics: OCR completed");
    }

    /**
     * Record OCR failure.
     */
    public void recordOCRFailed() {
        initializeMetricsIfNeeded();
        ocrFailedCounter.increment();
        log.debug("Metrics: OCR failed");
    }

    /**
     * Record OCR processing time in milliseconds.
     *
     * @param durationMillis time in milliseconds
     */
    public void recordOCRProcessingTime(long durationMillis) {
        initializeMetricsIfNeeded();
        ocrProcessingTimer.record(durationMillis, TimeUnit.MILLISECONDS);
        log.debug("Metrics: OCR processing took {} ms", durationMillis);
    }

    // ==================== AI Analysis Metrics Methods ====================

    /**
     * Record successful AI analysis completion.
     */
    public void recordAIAnalysisCompleted() {
        initializeMetricsIfNeeded();
        aiAnalysesCompletedCounter.increment();
        log.debug("Metrics: AI analysis completed");
    }

    /**
     * Record AI analysis failure.
     */
    public void recordAIAnalysisFailed() {
        initializeMetricsIfNeeded();
        aiAnalysesFailedCounter.increment();
        log.debug("Metrics: AI analysis failed");
    }

    /**
     * Record AI analysis time in milliseconds.
     *
     * @param durationMillis time in milliseconds
     */
    public void recordAIAnalysisTime(long durationMillis) {
        initializeMetricsIfNeeded();
        aiAnalysisTimer.record(durationMillis, TimeUnit.MILLISECONDS);
        log.debug("Metrics: AI analysis took {} ms", durationMillis);
    }

    // ==================== Policy Engine Metrics Methods ====================

    /**
     * Record policy approval.
     */
    public void recordPolicyApproved() {
        initializeMetricsIfNeeded();
        policyApprovedCounter.increment();
        log.debug("Metrics: Policy approved");
    }

    /**
     * Record policy hold.
     */
    public void recordPolicyHold() {
        initializeMetricsIfNeeded();
        policyHoldCounter.increment();
        log.debug("Metrics: Policy hold");
    }

    /**
     * Record number of policy rules evaluated.
     *
     * @param count number of rules evaluated
     */
    public void recordPolicyRulesEvaluated(int count) {
        initializeMetricsIfNeeded();
        policyRulesEvaluatedCounter.increment(count);
        log.debug("Metrics: {} policy rules evaluated", count);
    }

    /**
     * Record policy evaluation time in milliseconds.
     *
     * @param durationMillis time in milliseconds
     */
    public void recordPolicyEvaluationTime(long durationMillis) {
        initializeMetricsIfNeeded();
        policyEvaluationTimer.record(durationMillis, TimeUnit.MILLISECONDS);
        log.debug("Metrics: Policy evaluation took {} ms", durationMillis);
    }

    // ==================== WhatsApp Metrics Methods ====================

    /**
     * Record WhatsApp message sent.
     */
    public void recordWhatsAppMessageSent() {
        initializeMetricsIfNeeded();
        whatsappMessagesSentCounter.increment();
        log.debug("Metrics: WhatsApp message sent");
    }

    /**
     * Record WhatsApp message delivered.
     */
    public void recordWhatsAppMessageDelivered() {
        initializeMetricsIfNeeded();
        whatsappMessagesDeliveredCounter.increment();
        log.debug("Metrics: WhatsApp message delivered");
    }

    /**
     * Record WhatsApp media received.
     */
    public void recordWhatsAppMediaReceived() {
        initializeMetricsIfNeeded();
        whatsappMediaReceivedCounter.increment();
        log.debug("Metrics: WhatsApp media received");
    }

    // ==================== Webhook Metrics Methods ====================

    /**
     * Record webhook event received.
     */
    public void recordWebhookEventReceived() {
        initializeMetricsIfNeeded();
        webhookEventsReceivedCounter.increment();
        log.debug("Metrics: Webhook event received");
    }

    /**
     * Record webhook event processed successfully.
     */
    public void recordWebhookEventProcessed() {
        initializeMetricsIfNeeded();
        webhookEventsProcessedCounter.increment();
        log.debug("Metrics: Webhook event processed");
    }

    /**
     * Record webhook processing failure.
     */
    public void recordWebhookEventFailed() {
        initializeMetricsIfNeeded();
        webhookEventsFailedCounter.increment();
        log.debug("Metrics: Webhook event failed");
    }
}
