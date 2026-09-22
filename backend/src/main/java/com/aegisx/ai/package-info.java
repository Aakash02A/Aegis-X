/**
 * AI module: Spring AI regulatory reasoning layer.
 * 
 * Purpose:
 * - Consume DocumentExtraction records (OCR structured output)
 * - Produce advisory compliance findings with confidence scores
 * - Persist all analyses immutably for auditability
 * - Never approve, reject, or authorize payments
 * 
 * Architecture:
 * - ai.entity: AIAnalysis JPA entity for persistence
 * - ai.repository: AIAnalysisRepository for data access
 * - ai.dto: AIAnalysisResponse, ComplianceFinding for API contracts
 * - ai.service: AIAnalysisService, AIResponseParser, ConfidenceNormalizer, AIProcessingCoordinator
 * - ai.prompt: RegulatoryPromptBuilder for prompt template generation
 * - ai.controller: AIAnalysisController for REST endpoints
 * 
 * Frozen Invariants:
 * - AI consumes only DocumentExtraction, never raw files
 * - AI produces advisory findings only
 * - Every response persists before consumption
 * - Transaction stays in AI_ANALYSIS until Phase 10 Policy Engine acts
 * - Java Policy Engine remains sole authority for approval/rejection
 */
package com.aegisx.ai;
