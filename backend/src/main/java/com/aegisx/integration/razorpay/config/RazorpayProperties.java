package com.aegisx.integration.razorpay.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Data
@Configuration
@ConfigurationProperties(prefix = "razorpay")
public class RazorpayProperties {

    private static final String TEST_KEY_PREFIX = "rzp_test_";

    private String keyId;
    private String keySecret;
    private String webhookSecret;
    private String apiBaseUrl = "https://api.razorpay.com/v1";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(15);

    public boolean isConfigured() {
        return keyId != null && keyId.startsWith(TEST_KEY_PREFIX)
                && keySecret != null && !keySecret.isBlank();
    }
}
