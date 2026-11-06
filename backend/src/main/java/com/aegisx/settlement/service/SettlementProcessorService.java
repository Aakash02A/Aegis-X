package com.aegisx.settlement.service;

import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.settlement.entity.Settlement;
import com.aegisx.settlement.service.SettlementService.SettlementProcessorResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementProcessorService {

    private final RazorpayXClient razorpayXClient;
    private final Environment environment;
    private final ObjectMapper objectMapper;

    @Value("${settlement.mock-enabled:false}")
    private boolean mockEnabled;

    public SettlementProcessorResult submitSettlement(Settlement settlement) {
        return switch (settlement.getPaymentProcessor()) {
            case RAZORPAYX -> razorpayXClient.submitPayout(settlement);
            case MOCK -> submitMockSettlement(settlement);
            default -> throw new IllegalStateException(
                    "No live settlement integration is configured for " + settlement.getPaymentProcessor());
        };
    }

    public JsonNode querySettlementStatus(Settlement settlement) {
        if (settlement.getPaymentProcessor() != PaymentProcessor.RAZORPAYX
                || settlement.getProcessorTransactionId() == null) {
            throw new IllegalStateException("Settlement has no supported provider status lookup");
        }
        return razorpayXClient.fetchPayout(settlement.getProcessorTransactionId());
    }

    public PaymentProcessor[] getConfiguredProcessors() {
        return Arrays.stream(PaymentProcessor.values())
                .filter(this::validateProcessorConnection)
                .toArray(PaymentProcessor[]::new);
    }

    public boolean validateProcessorConnection(PaymentProcessor processor) {
        return switch (processor) {
            case RAZORPAYX -> razorpayXClient.isConfigured();
            case MOCK -> mockEnabled && isDevelopmentProfile();
            default -> false;
        };
    }

    private SettlementProcessorResult submitMockSettlement(Settlement settlement) {
        if (!validateProcessorConnection(PaymentProcessor.MOCK)) {
            throw new IllegalStateException("Mock settlement processing is disabled");
        }
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("messageType", "MOCK");
        metadata.put("environment", "TEST");
        metadata.put("processor", "MOCK_PROCESSOR");
        String processorTransactionId = "MOCK" + UUID.randomUUID().toString().substring(0, 8);
        metadata.put("processorTransactionId", processorTransactionId);
        metadata.put("status", "ACCEPTED");
        metadata.put("submittedAt", LocalDateTime.now().toString());
        metadata.put("testMode", true);
        log.info("Test-only mock settlement submitted: {}", settlement.getSettlementReference());
        return SettlementProcessorResult.builder()
                .processorTransactionId(processorTransactionId)
                .expectedCompletion(LocalDateTime.now().plusMinutes(1))
                .metadata(metadata)
                .success(true)
                .build();
    }

    private boolean isDevelopmentProfile() {
        return environment.acceptsProfiles(Profiles.of("dev", "test"));
    }
}
