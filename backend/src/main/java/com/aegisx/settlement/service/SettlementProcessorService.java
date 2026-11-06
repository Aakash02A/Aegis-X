package com.aegisx.settlement.service;

import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.settlement.entity.Settlement;
import com.aegisx.settlement.entity.SettlementStatus;
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

import java.util.Arrays;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementProcessorService {

    private final RazorpayXClient razorpayXClient;
    private final Environment environment;
    private final ObjectMapper objectMapper;

    @Value("${settlement.simulated-enabled:false}")
    private boolean simulatedEnabled;

    public SettlementProcessorResult submitSettlement(Settlement settlement) {
        return switch (settlement.getPaymentProcessor()) {
            case RAZORPAYX -> razorpayXClient.submitPayout(settlement);
            case SIMULATED -> submitSimulatedSettlement();
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
            case SIMULATED -> simulatedEnabled && isDevelopmentProfile();
            default -> false;
        };
    }

    private SettlementProcessorResult submitSimulatedSettlement() {
        if (!validateProcessorConnection(PaymentProcessor.SIMULATED)) {
            throw new IllegalStateException("Simulated settlement processing is disabled");
        }
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("executionMode", "SIMULATED");
        metadata.put("moneyMoved", false);
        metadata.put("status", "PENDING");
        log.info("Settlement marked SIMULATED; no funds moved");
        return SettlementProcessorResult.builder()
                .metadata(metadata)
                .initialStatus(SettlementStatus.PENDING)
                .success(true)
                .build();
    }

    private boolean isDevelopmentProfile() {
        return environment.acceptsProfiles(Profiles.of("dev", "test"));
    }
}
