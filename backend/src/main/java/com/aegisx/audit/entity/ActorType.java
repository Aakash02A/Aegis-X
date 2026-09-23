package com.aegisx.audit.entity;

/**
 * Enumeration of actor types for audit events.
 * Identifies who or what initiated the business event.
 */
public enum ActorType {
    /**
     * Human user (merchant, compliance officer, admin)
     */
    USER,

    /**
     * System-initiated event (scheduled job, automatic state machine transition)
     */
    SYSTEM,

    /**
     * External webhook (Razorpay, WhatsApp, etc.)
     */
    WEBHOOK,

    /**
     * Spring AI regulatory reasoner (advisory analysis)
     */
    AI,

    /**
     * Java Policy Engine (final compliance decision)
     */
    POLICY_ENGINE
}
