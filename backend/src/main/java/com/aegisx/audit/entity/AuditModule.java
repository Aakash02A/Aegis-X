package com.aegisx.audit.entity;

/**
 * Enumeration of modules that generate audit events.
 * Identifies the source module that initiated the business event.
 */
public enum AuditModule {
    /**
     * Authentication and authorization module
     */
    AUTH,

    /**
     * Transaction state machine and lifecycle management
     */
    TRANSACTION,

    /**
     * Razorpay payment gateway integration
     */
    RAZORPAY,

    /**
     * Document upload and management
     */
    DOCUMENT,

    /**
     * OCR (Optical Character Recognition) processing
     */
    OCR,

    /**
     * Spring AI regulatory reasoner
     */
    AI,

    /**
     * Java Policy Engine for compliance decisions
     */
    POLICY_ENGINE,

    /**
     * WhatsApp Business Cloud API integration
     */
    WHATSAPP,

    /**
     * Webhook reception and processing
     */
    WEBHOOK,

    /**
     * System-level events
     */
    SYSTEM
}
