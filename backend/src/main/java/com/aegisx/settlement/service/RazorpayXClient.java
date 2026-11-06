package com.aegisx.settlement.service;

import com.aegisx.settlement.config.RazorpayXProperties;
import com.aegisx.settlement.dto.SettlementRequest;
import com.aegisx.settlement.entity.Settlement;
import com.aegisx.settlement.entity.SettlementStatus;
import com.aegisx.settlement.service.SettlementService.SettlementProcessorResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
public class RazorpayXClient {

    private final RazorpayXProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public RazorpayXClient(RazorpayXProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getApiBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    public JsonNode createBeneficiaryFundAccount(Settlement settlement, SettlementRequest request) {
        ensureConfigured();
        String contactReference = settlement.getSettlementReference();
        JsonNode contact = post("/contacts", Map.of(
                "name", request.getBeneficiaryName().trim(),
                "type", "vendor",
                "reference_id", contactReference,
                "notes", Map.of("settlement_reference", contactReference)));
        String contactId = requiredText(contact, "id", "RazorpayX contact response is incomplete");

        JsonNode fundAccount = post("/fund_accounts", Map.of(
                "contact_id", contactId,
                "account_type", "bank_account",
                "bank_account", Map.of(
                        "name", request.getBeneficiaryName().trim(),
                        "ifsc", request.getBeneficiaryBankCode(),
                        "account_number", request.getBeneficiaryAccountNumber())));
        String fundAccountId = requiredText(fundAccount, "id", "RazorpayX fund-account response is incomplete");
        if (!fundAccount.path("active").asBoolean(false)) {
            throw new RazorpayXProviderException("RazorpayX beneficiary fund account is not active");
        }

        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("contactId", contactId);
        metadata.put("fundAccountId", fundAccountId);
        return metadata;
    }

    public SettlementProcessorResult submitPayout(Settlement settlement) {
        ensureConfigured();
        String fundAccountId = settlement.getProcessorMetadata() == null
                ? null : settlement.getProcessorMetadata().path("fundAccountId").asText(null);
        if (fundAccountId == null || fundAccountId.isBlank()) {
            throw new RazorpayXProviderException("RazorpayX beneficiary account is not registered");
        }

        long amountMinor = toMinorUnits(settlement.getSettlementAmount());
        int idempotencyAttempt = settlement.getProcessorTransactionId() == null
                ? 0 : settlement.getRetryCount();
        String idempotencyKey = UUID.nameUUIDFromBytes(
                ("aegisx-razorpayx-payout:" + settlement.getId() + ":" + idempotencyAttempt)
                        .getBytes(StandardCharsets.UTF_8)).toString();
        JsonNode payout = post("/payouts", Map.of(
                "account_number", properties.getAccountNumber(),
                "fund_account_id", fundAccountId,
                "amount", amountMinor,
                "currency", "INR",
                "mode", normalizeMode(properties.getPayoutMode()),
                "purpose", "vendor bill",
                "queue_if_low_balance", true,
                "reference_id", settlement.getSettlementReference(),
                "narration", narration(settlement.getSettlementReference())), "X-Payout-Idempotency", idempotencyKey);

        String payoutId = requiredText(payout, "id", "RazorpayX payout response is incomplete");
        String statusText = requiredText(payout, "status", "RazorpayX payout response is incomplete")
                .toLowerCase(Locale.ROOT);
        if (!settlement.getSettlementReference().equals(payout.path("reference_id").asText())
                || payout.path("amount").asLong(-1) != amountMinor
                || !"INR".equals(payout.path("currency").asText())) {
            throw new RazorpayXProviderException("RazorpayX payout response does not match the settlement");
        }

        SettlementStatus payoutStatus = mapStatus(statusText);
        ObjectNode metadata = settlement.getProcessorMetadata() instanceof ObjectNode existing
                ? existing.deepCopy() : objectMapper.createObjectNode();
        metadata.put("payoutId", payoutId);
        metadata.put("payoutStatus", statusText);
        if (payout.hasNonNull("utr")) {
            metadata.put("utr", payout.path("utr").asText());
        }

        return SettlementProcessorResult.builder()
                .processorTransactionId(payoutId)
                .metadata(metadata)
                .initialStatus(payoutStatus)
                .success(true)
                .build();
    }

    public JsonNode fetchPayout(String payoutId) {
        ensureConfigured();
        try {
            return restClient.get()
                    .uri("/payouts/{id}", payoutId)
                    .headers(headers -> headers.setBasicAuth(properties.getKeyId(), properties.getKeySecret()))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new RazorpayXProviderException("RazorpayX payout lookup failed (HTTP "
                    + e.getStatusCode().value() + ")");
        } catch (ResourceAccessException e) {
            throw new RazorpayXProviderException("RazorpayX is unavailable for payout reconciliation");
        }
    }

    private JsonNode post(String path, Map<String, ?> body) {
        return post(path, body, null, null);
    }

    private JsonNode post(String path, Map<String, ?> body, String idempotencyHeader, String idempotencyKey) {
        ensureConfigured();
        try {
            return restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        headers.setBasicAuth(properties.getKeyId(), properties.getKeySecret());
                        if (idempotencyHeader != null) {
                            headers.set(idempotencyHeader, idempotencyKey);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new RazorpayXProviderException("RazorpayX rejected the request (HTTP "
                    + e.getStatusCode().value() + ")");
        } catch (ResourceAccessException e) {
            throw new RazorpayXProviderException("RazorpayX is unavailable; retry with the same settlement reference");
        }
    }

    private void ensureConfigured() {
        if (!properties.isConfigured()) {
            throw new RazorpayXProviderException("RazorpayX payouts are disabled or not fully configured");
        }
        normalizeMode(properties.getPayoutMode());
    }

    private String requiredText(JsonNode node, String field, String error) {
        String value = node == null ? null : node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new RazorpayXProviderException(error);
        }
        return value;
    }

    private long toMinorUnits(BigDecimal amount) {
        try {
            long minorUnits = amount.movePointRight(2).longValueExact();
            if (minorUnits < 100) {
                throw new RazorpayXProviderException("RazorpayX payout minimum is INR 1.00");
            }
            return minorUnits;
        } catch (ArithmeticException e) {
            throw new RazorpayXProviderException("Settlement amount cannot be represented in INR paise");
        }
    }

    private String normalizeMode(String mode) {
        String normalized = mode == null ? "" : mode.toUpperCase(Locale.ROOT);
        if (!normalized.equals("NEFT") && !normalized.equals("RTGS") && !normalized.equals("IMPS")) {
            throw new RazorpayXProviderException("RazorpayX payout mode must be NEFT, RTGS, or IMPS");
        }
        return normalized;
    }

    private String narration(String settlementReference) {
        String narration = ("AegisX " + settlementReference).replaceAll("[^A-Za-z0-9 ]", "");
        return narration.substring(0, Math.min(30, narration.length()));
    }

    private SettlementStatus mapStatus(String status) {
        return switch (status) {
            case "queued", "pending", "processing", "initiated" -> SettlementStatus.PENDING;
            case "processed" -> SettlementStatus.COMPLETED;
            case "failed", "reversed" -> SettlementStatus.FAILED;
            case "rejected" -> SettlementStatus.REJECTED;
            case "cancelled" -> SettlementStatus.CANCELLED;
            default -> SettlementStatus.MANUAL_REVIEW;
        };
    }

    public static class RazorpayXProviderException extends RuntimeException {
        public RazorpayXProviderException(String message) {
            super(message);
        }
    }
}
