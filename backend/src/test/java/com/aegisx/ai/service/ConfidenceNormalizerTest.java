package com.aegisx.ai.service;

import com.aegisx.common.exception.InvalidAIResponseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConfidenceNormalizer Unit Tests")
class ConfidenceNormalizerTest {
    
    private ConfidenceNormalizer normalizer;
    
    @BeforeEach
    void setUp() {
        normalizer = new ConfidenceNormalizer();
    }
    
    @Test
    @DisplayName("Should normalize valid double value in range")
    void testNormalizeValidDouble() {
        BigDecimal result = normalizer.normalize(85.5);
        assertEquals(new BigDecimal("85.50"), result);
    }
    
    @Test
    @DisplayName("Should normalize zero confidence")
    void testNormalizeZero() {
        BigDecimal result = normalizer.normalize(0.0);
        assertEquals(BigDecimal.ZERO.setScale(2), result);
    }
    
    @Test
    @DisplayName("Should normalize 100 confidence")
    void testNormalize100() {
        BigDecimal result = normalizer.normalize(100.0);
        assertEquals(new BigDecimal("100.00"), result);
    }
    
    @Test
    @DisplayName("Should clamp value above 100 to 100")
    void testClampAbove100() {
        BigDecimal result = normalizer.normalize(105.5);
        assertEquals(new BigDecimal("100.00"), result);
    }
    
    @Test
    @DisplayName("Should clamp negative value to 0")
    void testClampNegative() {
        BigDecimal result = normalizer.normalize(-10.0);
        assertEquals(BigDecimal.ZERO.setScale(2), result);
    }
    
    @Test
    @DisplayName("Should round to 2 decimal places")
    void testRoundingTwoDecimals() {
        BigDecimal result = normalizer.normalize(75.555);
        assertEquals(new BigDecimal("75.56"), result);
    }
    
    @Test
    @DisplayName("Should reject null confidence")
    void testRejectNull() {
        assertThrows(InvalidAIResponseException.class, () -> normalizer.normalize((Double) null));
    }
    
    @Test
    @DisplayName("Should reject NaN confidence")
    void testRejectNaN() {
        assertThrows(InvalidAIResponseException.class, () -> normalizer.normalize(Double.NaN));
    }
    
    @Test
    @DisplayName("Should reject infinite confidence")
    void testRejectInfinite() {
        assertThrows(InvalidAIResponseException.class, () -> normalizer.normalize(Double.POSITIVE_INFINITY));
    }
    
    @Test
    @DisplayName("Should validate correct range")
    void testIsValidCorrectRange() {
        assertTrue(normalizer.isValid(50.0));
        assertTrue(normalizer.isValid(0.0));
        assertTrue(normalizer.isValid(100.0));
    }
    
    @Test
    @DisplayName("Should reject out of range values")
    void testIsValidOutOfRange() {
        assertFalse(normalizer.isValid(105.0));
        assertFalse(normalizer.isValid(-5.0));
        assertFalse(normalizer.isValid(Double.NaN));
        assertFalse(normalizer.isValid(null));
    }
    
    @Test
    @DisplayName("Should normalize BigDecimal value")
    void testNormalizeBigDecimal() {
        BigDecimal result = normalizer.normalize(new BigDecimal("75.5"));
        assertEquals(new BigDecimal("75.50"), result);
    }
}
