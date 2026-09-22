package com.aegisx.ai.service;

import com.aegisx.ai.dto.AIAnalysisResponse;
import com.aegisx.ai.entity.AIAnalysis;
import com.aegisx.ai.prompt.RegulatoryPromptBuilder;
import com.aegisx.ai.repository.AIAnalysisRepository;
import com.aegisx.common.exception.AIAnalysisException;
import com.aegisx.common.exception.InvalidAIResponseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * AIAnalysisService orchestrates Spring AI regulatory analysis.
 * 
 * Purpose:
 * - Call Spring AI with regulatory prompt
 * - Generate input hash for reproducibility and idempotency
 * - Parse and validate AI response
 * - Persist analysis to database
 * - Handle errors gracefully without losing audit history
 * 
 * Design:
 * - Deterministic reasoning: temperature 0.0
 * - Immutable storage: every response persists before consumption
 * - Full auditability: model_name, prompt_version, input_hash stored
 * - No approval authority: advisory findings only
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIAnalysisService {
    
    private final ChatClient chatClient;
    private final AIAnalysisRepository aiAnalysisRepository;
    private final AIResponseParser aiResponseParser;
    private final ObjectMapper objectMapper;
    
    @Value("${aegisx.ai.prompt-version:v1}")
    private String promptVersion;
    
    @Value("${aegisx.ai.model-name:gpt-4o}")
    private String modelName;
    
    /**
     * Perform regulatory analysis on a transaction's OCR extraction.
     * 
     * @param input Analysis request containing transaction and OCR data
     * @return Persisted analysis with advisory findings
     * @throws AIAnalysisException if analysis fails
     */
    @Transactional
    public AIAnalysis performAnalysis(AIAnalysisInput input) {
        long startTime = System.currentTimeMillis();
        
        try {
            // Generate input hash for reproducibility
            String inputHash = generateInputHash(input);
            
            // Check for duplicate analysis (idempotency)
            var existingAnalysis = aiAnalysisRepository.findByInputHash(inputHash);
            if (existingAnalysis.isPresent()) {
                log.info("Analysis already exists for input hash: {}", inputHash);
                return existingAnalysis.get();
            }
            
            // Build regulatory prompt
            RegulatoryPromptBuilder.PromptInput promptInput = RegulatoryPromptBuilder.PromptInput.builder()
                .transactionId(input.getTransactionId().toString())
                .purpose(input.getPurpose())
                .beneficiaryName(input.getBeneficiaryName())
                .amount(input.getAmount())
                .currency(input.getCurrency())
                .ocrJson(input.getOcrJson())
                .extractedText(input.getExtractedText())
                .build();
            
            String prompt = RegulatoryPromptBuilder.buildPrompt(promptInput);
            log.debug("Sending regulatory prompt for transaction: {}", input.getTransactionId());
            
            // Call Spring AI
            String aiResponse = chatClient.prompt(prompt)
                .call()
                .content();
            
            log.debug("Received AI response for transaction: {}", input.getTransactionId());
            
            // Parse and validate response
            AIAnalysisResponse analysisResponse = aiResponseParser.parse(
                aiResponse,
                input.getTransactionId(),
                input.getExtractionId(),
                modelName,
                promptVersion
            );
            
            // Calculate processing duration
            long processingDurationMs = System.currentTimeMillis() - startTime;
            
            // Persist analysis
            AIAnalysis analysis = AIAnalysis.builder()
                .transactionId(input.getTransactionId())
                .extractionId(input.getExtractionId())
                .modelName(modelName)
                .promptVersion(promptVersion)
                .inputHash(inputHash)
                .analysisJson(aiResponseParser.parseToJsonNode(aiResponse))
                .confidenceScore(analysisResponse.getOverallConfidence())
                .processingDurationMs(processingDurationMs)
                .build();
            
            AIAnalysis saved = aiAnalysisRepository.save(analysis);
            log.info("Analysis persisted: id={}, transactionId={}, confidence={}, durationMs={}", 
                saved.getId(), saved.getTransactionId(), saved.getConfidenceScore(), processingDurationMs);
            
            return saved;
            
        } catch (InvalidAIResponseException e) {
            log.error("Invalid AI response for transaction {}: {}", input.getTransactionId(), e.getMessage(), e);
            throw new AIAnalysisException("AI response validation failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("AI analysis failed for transaction {}: {}", input.getTransactionId(), e.getMessage(), e);
            throw new AIAnalysisException("AI analysis failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Generate SHA-256 input hash for reproducibility and idempotency.
     * Hash input: transactionId + ocrJson + extractedText
     */
    private String generateInputHash(AIAnalysisInput input) {
        try {
            StringBuilder hashInput = new StringBuilder();
            hashInput.append(input.getTransactionId().toString());
            hashInput.append("|");
            hashInput.append(input.getOcrJson() != null ? input.getOcrJson().toString() : "");
            hashInput.append("|");
            hashInput.append(input.getExtractedText() != null ? input.getExtractedText() : "");
            
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(hashInput.toString().getBytes(StandardCharsets.UTF_8));
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            
            return hexString.toString();
        } catch (Exception e) {
            throw new AIAnalysisException("Failed to generate input hash: " + e.getMessage(), e);
        }
    }
    
    /**
     * Input data for AI analysis.
     */
    @lombok.Data
    @lombok.Builder
    public static class AIAnalysisInput {
        private UUID transactionId;
        private UUID extractionId;
        private String purpose;
        private String beneficiaryName;
        private BigDecimal amount;
        private String currency;
        private JsonNode ocrJson;
        private String extractedText;
    }
}
