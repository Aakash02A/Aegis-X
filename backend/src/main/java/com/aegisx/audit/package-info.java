/**
 * Audit and monitoring module for Aegis-X.
 * 
 * This module provides:
 * - Centralized, immutable audit trail for all business events
 * - Correlation ID tracing across module boundaries
 * - Structured logging with transaction context
 * - Business metrics and operational monitoring
 * - RBAC-protected audit timeline APIs
 * 
 * Subpackages:
 * - entity: AuditEvent domain model and related enums (ActorType, AuditEventType, AuditModule)
 * - repository: Data access for audit events
 * - dto: Response objects for audit APIs
 * - service: AuditService (record events) and AuditEventFactory (standardized event creation)
 * - controller: REST APIs for audit timeline and search
 * 
 * Key Architectural Principles:
 * 1. Audit records are append-only. No updates or deletes allowed.
 * 2. Every state transition generates an audit event.
 * 3. Correlation IDs propagate across the entire request lifecycle.
 * 4. All sensitive information (passwords, tokens, secrets) is redacted from logs.
 * 5. Monitoring never changes business behavior—only observability.
 * 
 * Integration Points:
 * - Every module calls auditService.record(...) instead of constructing events manually.
 * - CorrelationIdFilter injects X-Correlation-ID into MDC for structured logging.
 * - BusinessMetricsService tracks key operational metrics via Micrometer.
 * - AuditController exposes REST APIs for timeline and search.
 */
package com.aegisx.audit;
