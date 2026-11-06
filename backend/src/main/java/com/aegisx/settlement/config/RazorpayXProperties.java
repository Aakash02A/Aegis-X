package com.aegisx.settlement.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Data
@Configuration
@ConfigurationProperties(prefix = "razorpayx")
public class RazorpayXProperties {

    private boolean enabled;
    private String keyId;
    private String keySecret;
    private String accountNumber;
    private String apiBaseUrl = "https://api.razorpay.com/v1";
    private String payoutMode = "NEFT";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(15);

    public boolean isConfigured() {
        return enabled
                && hasText(keyId)
                && hasText(keySecret)
                && hasText(accountNumber);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
