package com.aegisx.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSafetyChecksTest {

    @Test
    void rejectsMissingJwtSecret() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> checks("", "https://app.example.com").afterPropertiesSet());

        assertTrue(error.getMessage().contains("JWT_SECRET is required"));
    }

    @Test
    void rejectsWeakJwtSecret() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> checks("short", "https://app.example.com").afterPropertiesSet());

        assertTrue(error.getMessage().contains("at least 32 bytes"));
    }

    @Test
    void rejectsPlaceholderJwtSecret() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> checks("CHANGE_ME_replace_this_value", "https://app.example.com").afterPropertiesSet());

        assertTrue(error.getMessage().contains("placeholder"));
    }

    @Test
    void rejectsMissingCorsOrigins() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> checks("x".repeat(32), "").afterPropertiesSet());

        assertTrue(error.getMessage().contains("CORS_ALLOWED_ORIGINS is required"));
    }

    @Test
    void rejectsWildcardCorsOrigins() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> checks("x".repeat(32), "https://app.example.com,*").afterPropertiesSet());

        assertTrue(error.getMessage().contains("must not contain '*'"));
    }

    @Test
    void acceptsStrongSecretAndExplicitOrigins() {
        assertDoesNotThrow(() -> checks("x".repeat(32), "https://app.example.com,https://ops.example.com")
                .afterPropertiesSet());
    }

    private ProductionSafetyChecks checks(String secret, String origins) {
        ProductionSafetyChecks checks = new ProductionSafetyChecks();
        ReflectionTestUtils.setField(checks, "jwtSecret", secret);
        ReflectionTestUtils.setField(checks, "allowedOrigins", origins);
        return checks;
    }
}
