package com.aegisx.ai.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

/**
 * RegulatoryPromptBuilder constructs deterministic AI prompts for compliance analysis.
 * 
 * Purpose:
 * - Build structured prompts receiving transaction details, OCR JSON, extracted text
 * - Instruct AI to identify inconsistencies and missing evidence
 * - Return strict JSON findings only
 * - Never ask AI to approve or reject payments
 * - Enable reproducibility and auditability through versioned prompts
 * 
 * Design:
 * - Temperature 0.0: Deterministic reasoning style
 * - Structured input: transaction, OCR JSON, extracted text, purpose code, beneficiary, amount, currency
 * - JSON output constraint: strict validation required
 * - Evidence-backed findings: every observation includes supporting evidence
 * 
 * Example Input:
 * - transactionId: "uuid"
 * - purpose: "Export of electronics"
 * - beneficiaryName: "XYZ Imports LLC"
 * - amount: 50000.00
 * - currency: "USD"
 * - ocrJson: { extracted fields from document }
 * - extractedText: "raw OCR text from invoice"
 * 
 * Example Output (AI must return this JSON structure):
 * {
 *   "overallConfidence": 94.5,
 *   "summary": "Invoice appears internally consistent.",
 *   "findings": [
 *     {
 *       "type": "BENEFICIARY_MATCH",
 *       "severity": "LOW",
 *       "confidence": 99,
 *       "evidence": "Invoice beneficiary matches transaction."
 *     }
 *   ],
 *   "missingEvidence": ["Export declaration not available."],
 *   "recommendedChecks": ["Verify FEMA purpose code against merchant category."]
 * }
 */
@Slf4j
public class RegulatoryPromptBuilder {
    
    /**
     * Build a regulatory analysis prompt from transaction and OCR data.
     */
    public static String buildPrompt(PromptInput input) {
        return """
You are a regulatory compliance analysis AI for cross-border payment processing.

Your role: Analyze transaction evidence and identify inconsistencies or missing information.
Your output: Structured JSON findings with confidence scores.
Your constraint: Never approve or reject payments. Only provide advisory findings.

=== TRANSACTION DETAILS ===
Transaction ID: {TRANSACTION_ID}
Purpose: {PURPOSE}
Beneficiary: {BENEFICIARY}
Amount: {AMOUNT} {CURRENCY}

=== OCR STRUCTURED DATA ===
{OCR_JSON}

=== EXTRACTED TEXT FROM DOCUMENT ===
{EXTRACTED_TEXT}

=== ANALYSIS INSTRUCTIONS ===
1. Compare transaction details (purpose, beneficiary, amount) against OCR extracted data
2. Identify any mismatches or inconsistencies between fields
3. Note any missing critical evidence (export declarations, bills of lading, invoices, etc.)
4. For each finding, provide:
   - type: category of finding (e.g., BENEFICIARY_MISMATCH, MISSING_INVOICE)
   - severity: HIGH, MEDIUM, or LOW
   - confidence: 0-100 representing your confidence in this finding
   - evidence: specific supporting data from documents
5. Provide overall confidence (0-100) for the entire analysis
6. Suggest additional checks based on findings

=== OUTPUT REQUIREMENT ===
Return ONLY valid JSON, no other text. Structure:
{
  "overallConfidence": <0-100>,
  "summary": "<brief summary of findings>",
  "findings": [
    {
      "type": "<finding type>",
      "severity": "<HIGH|MEDIUM|LOW>",
      "message": "<human-readable message>",
      "confidence": <0-100>,
      "evidence": "<specific evidence from documents>"
    }
  ],
  "missingEvidence": ["<list of missing documents>"],
  "recommendedChecks": ["<list of recommended verification steps>"]
}

Begin analysis now.
"""
            .replace("{TRANSACTION_ID}", input.getTransactionId())
            .replace("{PURPOSE}", escapeJson(input.getPurpose()))
            .replace("{BENEFICIARY}", escapeJson(input.getBeneficiaryName()))
            .replace("{AMOUNT}", input.getAmount().toPlainString())
            .replace("{CURRENCY}", input.getCurrency())
            .replace("{OCR_JSON}", input.getOcrJson() != null ? input.getOcrJson().toString() : "{}")
            .replace("{EXTRACTED_TEXT}", escapeJson(truncateText(input.getExtractedText(), 5000)));
    }
    
    /**
     * Escape text for safe inclusion in JSON strings.
     */
    private static String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
    
    /**
     * Truncate text to prevent prompt injection and token limit issues.
     */
    private static String truncateText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() > maxLength) {
            return text.substring(0, maxLength) + "... [truncated]";
        }
        return text;
    }
    
    /**
     * Input data for prompt generation.
     */
    @Data
    @Builder
    public static class PromptInput {
        private String transactionId;
        private String purpose;
        private String beneficiaryName;
        private BigDecimal amount;
        private String currency;
        private JsonNode ocrJson;
        private String extractedText;
    }
}
