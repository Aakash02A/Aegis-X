package com.aegisx.common.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Refuses to start the application with configuration that would be unsafe in production.
 *
 * <p>Both checks exist because their failure modes are silent. A short or absent JWT secret still
 * signs tokens, and a wildcard CORS origin still answers preflights; the application looks healthy
 * while every token is forgeable or every website can call the API with the user's credentials.
 * Failing at startup turns those into a deployment error instead of a live vulnerability.</p>
 *
 * <p>The development and test profiles are exempt, so a fresh checkout still runs without setup.</p>
 */
@Component
@Profile("prod")
public class ProductionSafetyChecks implements InitializingBean {

    /** SHA-256 hash of legacy published example secret (raw high-entropy strings must not appear in code). */
    private static final String LEGACY_EXAMPLE_SECRET_SHA256 =
            "3354cfcf65c7b2e6840ea6f880aede239750cb1d063950c4e8fd9fbf9ec73a32";

    private static final int MIN_SECRET_BYTES = 32;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${aegisx.cors.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void afterPropertiesSet() {
        requireStrongJwtSecret();
        requireExplicitCorsOrigins();
    }

    private void requireStrongJwtSecret() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is required with the prod profile. Generate one with "
                            + "`openssl rand -base64 48` and inject it as a secret.");
        }
        if (jwtSecret.startsWith("CHANGE_ME") || isLegacyExampleSecret(jwtSecret)) {
            throw new IllegalStateException(
                    "JWT_SECRET still has the default/placeholder value from .env.example. Generate a unique secret with `openssl rand -base64 48`.");
        }
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes (HS256 requirement); "
                            + "the supplied value is " + jwtSecret.getBytes(StandardCharsets.UTF_8).length + " bytes.");
        }
    }

    private boolean isLegacyExampleSecret(String secret) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return LEGACY_EXAMPLE_SECRET_SHA256.equalsIgnoreCase(hexString.toString());
        } catch (Exception e) {
            return false;
        }
    }

    private void requireExplicitCorsOrigins() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            throw new IllegalStateException(
                    "CORS_ALLOWED_ORIGINS is required with the prod profile. List the exact frontend origins, "
                            + "for example https://app.example.com");
        }
        boolean wildcard = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .anyMatch(origin -> origin.equals("*"));
        if (wildcard) {
            throw new IllegalStateException(
                    "CORS_ALLOWED_ORIGINS must not contain '*': the API allows credential-bearing requests, "
                            + "so every origin must be named explicitly.");
        }
    }
}
