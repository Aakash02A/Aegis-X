package com.aegisx.ai.entity;

import com.aegisx.common.entity.BaseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AIAnalysis entity represents an immutable record of Spring AI regulatory reasoning.
 * 
 * Purpose:
 * - Persist all AI compliance analysis findings before consumption
 * - Enable full auditability of AI decisions and confidence scoring
 * - Support prompt versioning and input hash tracking for reproducibility
 * - Never overwrite: each analysis is immutable; only insert operations allowed
 * 
 * Constraints:
 * - AI produces advisory findings only; never authorizes or blocks payments
 * - Confidence scores range from 0.0 to 100.0
 * - Input hash enables future duplicate detection and idempotency
 * - Transaction remains in AI_ANALYSIS state until Phase 10 Policy Engine takes action
 */
@Entity
@Table(name = "ai_analysis", indexes = {
    @Index(name = "idx_ai_analysis_transaction_id", columnList = "transaction_id"),
    @Index(name = "idx_ai_analysis_extraction_id", columnList = "extraction_id"),
    @Index(name = "idx_ai_analysis_created_at", columnList = "created_at DESC"),
    @Index(name = "idx_ai_analysis_input_hash", columnList = "input_hash")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AIAnalysis {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;
    
    @Column(name = "extraction_id", nullable = false)
    private UUID extractionId;
    
    @Column(name = "model_name", nullable = false, length = 255)
    private String modelName;
    
    @Column(name = "prompt_version", nullable = false, length = 50)
    private String promptVersion;
    
    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;
    
    @Column(name = "analysis_json", nullable = false, columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode analysisJson;
    
    @Column(name = "confidence_score", nullable = false)
    private BigDecimal confidenceScore;
    
    @Column(name = "processing_duration_ms", nullable = false)
    private Long processingDurationMs;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
