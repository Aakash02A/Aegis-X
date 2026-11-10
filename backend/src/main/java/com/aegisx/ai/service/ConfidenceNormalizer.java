package com.aegisx.ai.service;

import com.aegisx.common.exception.InvalidAIResponseException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * ConfidenceNormalizer ensures AI confidence scores are valid and within range [0, 100].
 * 
 * Purpose:
 * - Validate all confidence values from AI response
 * - Reject out-of-range values instead of silently making malformed output look valid
 * - Ensure consistent decimal precision (2 decimal places)
 * - Provide clear error messages for invalid inputs
 */
@Component
public class ConfidenceNormalizer {
    
    private static final BigDecimal MIN_CONFIDENCE = BigDecimal.ZERO;
    private static final BigDecimal MAX_CONFIDENCE = new BigDecimal("100");
    private static final int SCALE = 2;
    
    /**
     * Normalize a single confidence value.
     * 
     * @param confidence Raw confidence value
     * @return Normalized BigDecimal in range [0, 100] with 2 decimal places
     * @throws InvalidAIResponseException if confidence is null, unparseable or outside [0, 100]
     */
    public BigDecimal normalize(Double confidence) {
        if (confidence == null) {
            throw new InvalidAIResponseException("Confidence score cannot be null");
        }
        
        if (Double.isNaN(confidence) || Double.isInfinite(confidence)) {
            throw new InvalidAIResponseException("Confidence score is invalid (NaN or infinite): " + confidence);
        }
        
        BigDecimal bd = BigDecimal.valueOf(confidence);
        return normalizeDecimal(bd);
    }
    
    /**
     * Normalize a BigDecimal confidence value.
     */
    public BigDecimal normalize(BigDecimal confidence) {
        if (confidence == null) {
            throw new InvalidAIResponseException("Confidence score cannot be null");
        }
        return normalizeDecimal(confidence);
    }
    
    /**
     * Internal normalization logic.
     */
    private BigDecimal normalizeDecimal(BigDecimal confidence) {
        if (confidence.compareTo(MIN_CONFIDENCE) < 0 || confidence.compareTo(MAX_CONFIDENCE) > 0) {
            throw new InvalidAIResponseException("Confidence score is outside the allowed range [0, 100]");
        }
        
        // Round to 2 decimal places
        return confidence.setScale(SCALE, RoundingMode.HALF_UP);
    }
    
    /**
     * Validate that a confidence score is in valid range [0, 100].
     */
    public boolean isValid(Double confidence) {
        if (confidence == null || Double.isNaN(confidence) || Double.isInfinite(confidence)) {
            return false;
        }
        return confidence >= 0 && confidence <= 100;
    }
    
    /**
     * Validate that a confidence score is in valid range [0, 100].
     */
    public boolean isValid(BigDecimal confidence) {
        if (confidence == null) {
            return false;
        }
        return confidence.compareTo(MIN_CONFIDENCE) >= 0 && confidence.compareTo(MAX_CONFIDENCE) <= 0;
    }
}
