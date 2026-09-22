package com.aegisx.document.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Extracts structured fields from raw extracted text using deterministic patterns.
 * 
 * No LLM. Pure regex and pattern matching.
 * 
 * Extracted fields:
 * - invoiceNumber: Pattern-matched from common invoice formats
 * - invoiceDate: Date extracted and normalized to ISO 8601
 * - currency: Three-letter currency code (USD, EUR, INR, etc.)
 * - amount: Numeric value with 2 decimal places
 * - beneficiary: Company name or entity name from text
 * 
 * All extractions are heuristic-based and include confidence scores.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StructuredExtractionService {

    private final ObjectMapper objectMapper;

    /**
     * Extract structured fields from raw text.
     * 
     * @param rawText the extracted text
     * @return JsonNode containing structured fields and their confidence scores
     */
    public JsonNode extractStructuredData(String rawText) {
        log.debug("Starting structured extraction from text of length: {}", rawText.length());

        ObjectNode result = objectMapper.createObjectNode();

        // Extract each field
        String invoiceNumber = extractInvoiceNumber(rawText);
        result.put("invoiceNumber", invoiceNumber);
        result.put("invoiceNumber_confidence", invoiceNumber != null ? 90 : 0);

        LocalDate invoiceDate = extractInvoiceDate(rawText);
        if (invoiceDate != null) {
            result.put("invoiceDate", invoiceDate.toString());
            result.put("invoiceDate_confidence", 85);
        } else {
            result.put("invoiceDate_confidence", 0);
        }

        String currency = extractCurrency(rawText);
        result.put("currency", currency);
        result.put("currency_confidence", currency != null ? 95 : 0);

        BigDecimal amount = extractAmount(rawText);
        if (amount != null) {
            result.put("amount", amount);
            result.put("amount_confidence", 85);
        } else {
            result.put("amount_confidence", 0);
        }

        String beneficiary = extractBeneficiary(rawText);
        result.put("beneficiary", beneficiary);
        result.put("beneficiary_confidence", beneficiary != null ? 70 : 0);

        log.debug("Structured extraction completed");
        return result;
    }

    /**
     * Calculate overall confidence score based on individual field confidence.
     * 
     * @param structuredData the structured JSON output
     * @return average confidence across all fields (0–100)
     */
    public Double calculateConfidenceScore(JsonNode structuredData) {
        final double[] totalConfidence = {0};
        final int[] fieldCount = {0};

        // Iterate through confidence fields
        structuredData.fields().forEachRemaining(entry -> {
            if (entry.getKey().endsWith("_confidence")) {
                totalConfidence[0] += entry.getValue().asDouble();
                fieldCount[0]++;
            }
        });

        double avgConfidence = fieldCount[0] > 0 ? totalConfidence[0] / fieldCount[0] : 0;
        log.debug("Calculated confidence score: {}", avgConfidence);
        return avgConfidence;
    }

    /**
     * Extract invoice number using regex patterns.
     * Matches: INV-1024, Invoice #2026-09-001, INV/2026/09/001, etc.
     */
    private String extractInvoiceNumber(String text) {
        Pattern[] patterns = {
            Pattern.compile("(?:INV|Invoice|Bill|Invoice No\\.?|Inv\\.?)[\\s#-]*([A-Z0-9/-]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:Invoice Number|Invoice #)\\s*[:=]?\\s*([A-Z0-9/-]+)", Pattern.CASE_INSENSITIVE),
        };

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String invoiceNum = matcher.group(1).trim();
                log.debug("Extracted invoice number: {}", invoiceNum);
                return invoiceNum;
            }
        }

        log.debug("No invoice number found");
        return null;
    }

    /**
     * Extract invoice date using regex patterns.
     * Matches: 09/21/2026, 21-09-2026, 2026-09-21, 21 September 2026, etc.
     */
    private LocalDate extractInvoiceDate(String text) {
        DateTimeFormatter[] formatters = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        };

        // Pattern to match common date formats
        Pattern datePattern = Pattern.compile(
            "\\b(\\d{1,4}[/-]\\d{1,2}[/-]\\d{4}|\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{4})\\b",
            Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = datePattern.matcher(text);
        if (matcher.find()) {
            String dateStr = matcher.group(1);
            for (DateTimeFormatter formatter : formatters) {
                try {
                    LocalDate date = LocalDate.parse(dateStr, formatter);
                    log.debug("Extracted invoice date: {}", date);
                    return date;
                } catch (Exception e) {
                    // Try next formatter
                }
            }
        }

        log.debug("No invoice date found");
        return null;
    }

    /**
     * Extract currency code using regex.
     * Matches: USD, EUR, INR, GBP, JPY, AUD, CAD, CHF, CNY, SGD, HKD, NZD, etc.
     */
    private String extractCurrency(String text) {
        Pattern currencyPattern = Pattern.compile("\\b([A-Z]{3})\\b");
        String currencyCodes = "USD|EUR|INR|GBP|JPY|AUD|CAD|CHF|CNY|SGD|HKD|NZD|KRW|THB|MYR|PHP|IDR|VND|PKR|BDT|ZAR|NGN|KES|EGP|AED|SAR|QAR|KWD|BHD|OMR|JOD";
        Pattern knownCurrencies = Pattern.compile("\\b(" + currencyCodes + ")\\b");

        Matcher matcher = knownCurrencies.matcher(text);
        if (matcher.find()) {
            String currency = matcher.group(1);
            log.debug("Extracted currency: {}", currency);
            return currency;
        }

        log.debug("No currency found");
        return null;
    }

    /**
     * Extract amount using regex.
     * Matches: 2450.50, 2,450.50, $2450.50, USD 2450.50, etc.
     */
    private BigDecimal extractAmount(String text) {
        Pattern amountPattern = Pattern.compile(
            "(?:amount|total|price|cost|charges|value|invoiced|due)\\s*[:=]?\\s*(?:\\$|USD|EUR|INR)?\\s*([0-9,]+\\.?[0-9]*)",
            Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = amountPattern.matcher(text);
        if (matcher.find()) {
            String amountStr = matcher.group(1).replaceAll(",", "");
            try {
                BigDecimal amount = new BigDecimal(amountStr);
                log.debug("Extracted amount: {}", amount);
                return amount;
            } catch (NumberFormatException e) {
                log.debug("Failed to parse amount: {}", amountStr);
            }
        }

        // Fallback: look for any substantial number that looks like an amount
        Pattern fallbackPattern = Pattern.compile("\\b([0-9]{1,10}(?:[.,][0-9]{2})?)\\b");
        matcher = fallbackPattern.matcher(text);
        while (matcher.find()) {
            String numStr = matcher.group(1).replaceAll(",", "");
            try {
                BigDecimal num = new BigDecimal(numStr);
                // Filter to likely amounts (> 100, reasonable size)
                if (num.compareTo(new BigDecimal("100")) > 0 && num.compareTo(new BigDecimal("10000000")) < 0) {
                    log.debug("Extracted amount (fallback): {}", num);
                    return num;
                }
            } catch (NumberFormatException e) {
                // Continue searching
            }
        }

        log.debug("No amount found");
        return null;
    }

    /**
     * Extract beneficiary name using heuristics.
     * Looks for entity names, company names, or person names.
     */
    private String extractBeneficiary(String text) {
        Pattern[] patterns = {
            Pattern.compile("(?:beneficiary|payee|recipient|to\\s+the\\s+order\\s+of|make\\s+check\\s+payable\\s+to)\\s*[:=]?\\s*([A-Za-z\\s&.,'-]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:company|entity|business|firm|corporation)\\s*[:=]?\\s*([A-Za-z\\s&.,'-]+)", Pattern.CASE_INSENSITIVE),
        };

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String beneficiary = matcher.group(1).trim();
                if (beneficiary.length() > 3 && beneficiary.length() < 255) {
                    log.debug("Extracted beneficiary: {}", beneficiary);
                    return beneficiary;
                }
            }
        }

        log.debug("No beneficiary found");
        return null;
    }
}
