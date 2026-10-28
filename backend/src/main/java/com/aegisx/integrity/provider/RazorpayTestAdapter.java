package com.aegisx.integrity.provider;

import com.aegisx.integrity.config.IntegrityProperties;
import com.aegisx.integrity.domain.CapabilityStatus;
import com.aegisx.integrity.domain.OrderState;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.ProviderMode;
import com.aegisx.integrity.domain.RefundState;
import com.aegisx.integrity.domain.TransferState;
import com.aegisx.integrity.webhook.IntegrityWebhookSignatureService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter for the Razorpay <strong>Test Mode</strong> API.
 *
 * <p>Endpoints are exactly the documented ones (Orders {@code /orders}, Payments {@code /payments},
 * Refunds {@code /refunds}, Route {@code /transfers}). Nothing is invented, and no method here is
 * called automatically by recovery: the two mutating calls ({@code createOrder}, {@code
 * createRefund}) require an explicit, human-initiated request.</p>
 *
 * <p>Safety properties:</p>
 * <ul>
 *   <li>The adapter refuses to operate with a key id that does not look like a test key, so the
 *       prototype cannot be pointed at live money.</li>
 *   <li>Credentials come from the environment only and are never logged; the
 *       {@code Authorization} header is never included in any log line.</li>
 *   <li>A missing resource, an unreachable provider and an authentication rejection are three
 *       different outcomes, because reconciliation must not treat an outage as "no such payment".</li>
 * </ul>
 */
@Slf4j
@Component
public class RazorpayTestAdapter implements PaymentProviderAdapter {

    private static final String PROVIDER = "RAZORPAY";
    private static final String TEST_KEY_PREFIX = "rzp_test";

    private final IntegrityProperties properties;
    private final IntegrityWebhookSignatureService signatureService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public RazorpayTestAdapter(IntegrityProperties properties,
                               IntegrityWebhookSignatureService signatureService,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.signatureService = signatureService;
        this.objectMapper = objectMapper;
        this.restClient = buildClient(properties);
    }

    private static RestClient buildClient(IntegrityProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getRazorpay().getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getRazorpay().getReadTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(properties.getRazorpay().getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public ProviderMode mode() {
        return ProviderMode.RAZORPAY_TEST;
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public boolean isSimulated() {
        return false;
    }

    @Override
    public CapabilityStatus connectivity() {
        String blocked = configurationBlocker();
        if (blocked != null) {
            return CapabilityStatus.NOT_CONFIGURED;
        }
        ProviderOutcome<JsonNode> probe = get("/orders?count=1");
        return switch (probe.status()) {
            case OK -> CapabilityStatus.OK;
            case NOT_FOUND -> CapabilityStatus.OK;
            default -> CapabilityStatus.UNAVAILABLE;
        };
    }

    @Override
    public CapabilityStatus transferCapability() {
        if (configurationBlocker() != null) {
            return CapabilityStatus.NOT_CONFIGURED;
        }
        // Route is an account-level product; enablement cannot be inferred from a read call.
        return properties.getRazorpay().isRouteEnabled() ? CapabilityStatus.OK : CapabilityStatus.NOT_ENABLED;
    }

    @Override
    public ProviderOutcome<OrderSnapshot> createOrder(CreateOrderCommand command) {
        String blocker = configurationBlocker();
        if (blocker != null) {
            return ProviderOutcome.notConfigured(blocker);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", command.amountMinor());
        body.put("currency", command.currency());
        body.put("receipt", command.internalReference());
        if (command.description() != null) {
            body.put("description", command.description());
        }
        ProviderOutcome<JsonNode> response = post("/orders", body);
        if (!response.hasValue()) {
            return new ProviderOutcome<>(response.status(), null, response.detail(), false);
        }
        return ProviderOutcome.ok(toOrder(response.value()), false);
    }

    @Override
    public ProviderOutcome<OrderSnapshot> fetchOrder(String providerOrderId) {
        String blocker = configurationBlocker();
        if (blocker != null) {
            return ProviderOutcome.notConfigured(blocker);
        }
        ProviderOutcome<JsonNode> response = get("/orders/" + providerOrderId);
        if (!response.hasValue()) {
            return new ProviderOutcome<>(response.status(), null, response.detail(), false);
        }
        return ProviderOutcome.ok(toOrder(response.value()), false);
    }

    @Override
    public ProviderOutcome<List<PaymentSnapshot>> fetchOrderPayments(String providerOrderId) {
        String blocker = configurationBlocker();
        if (blocker != null) {
            return ProviderOutcome.notConfigured(blocker);
        }
        ProviderOutcome<JsonNode> response = get("/orders/" + providerOrderId + "/payments");
        if (!response.hasValue()) {
            return new ProviderOutcome<>(response.status(), null, response.detail(), false);
        }
        List<PaymentSnapshot> snapshots = new ArrayList<>();
        for (JsonNode item : items(response.value())) {
            snapshots.add(toPayment(item, providerOrderId));
        }
        return ProviderOutcome.ok(List.copyOf(snapshots), false);
    }

    @Override
    public ProviderOutcome<PaymentSnapshot> fetchPayment(String providerPaymentId) {
        String blocker = configurationBlocker();
        if (blocker != null) {
            return ProviderOutcome.notConfigured(blocker);
        }
        ProviderOutcome<JsonNode> response = get("/payments/" + providerPaymentId);
        if (!response.hasValue()) {
            return new ProviderOutcome<>(response.status(), null, response.detail(), false);
        }
        return ProviderOutcome.ok(toPayment(response.value(), null), false);
    }

    @Override
    public ProviderOutcome<RefundSnapshot> fetchRefund(String providerRefundId) {
        String blocker = configurationBlocker();
        if (blocker != null) {
            return ProviderOutcome.notConfigured(blocker);
        }
        ProviderOutcome<JsonNode> response = get("/refunds/" + providerRefundId);
        if (!response.hasValue()) {
            return new ProviderOutcome<>(response.status(), null, response.detail(), false);
        }
        return ProviderOutcome.ok(toRefund(response.value()), false);
    }

    @Override
    public ProviderOutcome<List<RefundSnapshot>> fetchPaymentRefunds(String providerPaymentId) {
        String blocker = configurationBlocker();
        if (blocker != null) {
            return ProviderOutcome.notConfigured(blocker);
        }
        ProviderOutcome<JsonNode> response = get("/payments/" + providerPaymentId + "/refunds");
        if (!response.hasValue()) {
            return new ProviderOutcome<>(response.status(), null, response.detail(), false);
        }
        List<RefundSnapshot> snapshots = new ArrayList<>();
        for (JsonNode item : items(response.value())) {
            snapshots.add(toRefund(item));
        }
        return ProviderOutcome.ok(List.copyOf(snapshots), false);
    }

    @Override
    public ProviderOutcome<TransferSnapshot> fetchTransfer(String providerTransferId) {
        if (transferCapability() != CapabilityStatus.OK) {
            return ProviderOutcome.notEnabled("Route is not enabled for this account; transfer verification is suppressed");
        }
        ProviderOutcome<JsonNode> response = get("/transfers/" + providerTransferId);
        if (!response.hasValue()) {
            return new ProviderOutcome<>(response.status(), null, response.detail(), false);
        }
        return ProviderOutcome.ok(toTransfer(response.value()), false);
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        return signatureService.verify(rawBody, signature, properties.getRazorpay().getWebhookSecret());
    }

    /**
     * Returns a non-secret explanation when the adapter cannot be used, or {@code null} when it can.
     * Enforces the test-mode-only rule rather than trusting configuration.
     */
    private String configurationBlocker() {
        IntegrityProperties.Razorpay razorpay = properties.getRazorpay();
        if (!razorpay.isConfigured()) {
            return "Razorpay test credentials are not configured (RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET)";
        }
        if (!razorpay.getKeyId().startsWith(TEST_KEY_PREFIX)) {
            return "Refusing to use a non-test key id: this prototype operates in Test Mode only";
        }
        return null;
    }

    private ProviderOutcome<JsonNode> get(String path) {
        try {
            JsonNode node = restClient.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            return ProviderOutcome.ok(node, false);
        } catch (RestClientResponseException e) {
            return mapHttpError(path, e);
        } catch (RuntimeException e) {
            log.warn("Razorpay GET {} failed: {}", path, e.getClass().getSimpleName());
            return ProviderOutcome.unavailable("provider call failed: " + e.getClass().getSimpleName(), false);
        }
    }

    private ProviderOutcome<JsonNode> post(String path, Map<String, Object> body) {
        try {
            JsonNode node = restClient.post()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return ProviderOutcome.ok(node, false);
        } catch (RestClientResponseException e) {
            return mapHttpError(path, e);
        } catch (RuntimeException e) {
            log.warn("Razorpay POST {} failed: {}", path, e.getClass().getSimpleName());
            return ProviderOutcome.unavailable("provider call failed: " + e.getClass().getSimpleName(), false);
        }
    }

    private ProviderOutcome<JsonNode> mapHttpError(String path, RestClientResponseException e) {
        int code = e.getStatusCode().value();
        // The response body is intentionally not logged: it can echo request parameters.
        log.warn("Razorpay {} returned HTTP {}", path, code);
        if (code == 404) {
            return ProviderOutcome.notFound("provider reports no such resource", false);
        }
        if (code == 401 || code == 403) {
            return ProviderOutcome.unavailable("provider rejected the credentials (HTTP " + code + ")", false);
        }
        return ProviderOutcome.unavailable("provider returned HTTP " + code, false);
    }

    private String authorizationHeader() {
        IntegrityProperties.Razorpay razorpay = properties.getRazorpay();
        String raw = razorpay.getKeyId() + ":" + razorpay.getKeySecret();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Iterable<JsonNode> items(JsonNode root) {
        JsonNode items = root.path("items");
        return items.isArray() ? items : List.of();
    }

    private OrderSnapshot toOrder(JsonNode node) {
        return new OrderSnapshot(
                text(node, "id"),
                mapOrderState(text(node, "status")),
                longValue(node, "amount"),
                text(node, "currency"),
                text(node, "receipt"),
                instant(node, "created_at"),
                Instant.now());
    }

    private PaymentSnapshot toPayment(JsonNode node, String fallbackOrderId) {
        String orderId = text(node, "order_id");
        return new PaymentSnapshot(
                text(node, "id"),
                orderId != null ? orderId : fallbackOrderId,
                mapPaymentState(text(node, "status")),
                longValue(node, "amount"),
                text(node, "currency"),
                text(node, "method"),
                text(node, "error_description"),
                instant(node, "created_at"),
                Instant.now());
    }

    private RefundSnapshot toRefund(JsonNode node) {
        return new RefundSnapshot(
                text(node, "id"),
                text(node, "payment_id"),
                mapRefundState(text(node, "status")),
                longValue(node, "amount"),
                text(node, "currency"),
                instant(node, "created_at"),
                Instant.now());
    }

    private TransferSnapshot toTransfer(JsonNode node) {
        String recipient = text(node, "recipient");
        return new TransferSnapshot(
                text(node, "id"),
                text(node, "source"),
                mapTransferState(text(node, "status")),
                longValue(node, "amount"),
                text(node, "currency"),
                recipient,
                instant(node, "created_at"),
                Instant.now());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static long longValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : 0L;
    }

    private static Instant instant(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? Instant.ofEpochSecond(value.asLong()) : Instant.now();
    }

    private static OrderState mapOrderState(String providerStatus) {
        if (providerStatus == null) {
            return OrderState.CREATED;
        }
        return switch (providerStatus) {
            case "paid" -> OrderState.PAID;
            case "attempted" -> OrderState.ATTEMPTED;
            default -> OrderState.CREATED;
        };
    }

    private static PaymentState mapPaymentState(String providerStatus) {
        if (providerStatus == null) {
            return PaymentState.CREATED;
        }
        return switch (providerStatus) {
            case "authorized" -> PaymentState.AUTHORIZED;
            case "captured" -> PaymentState.CAPTURED;
            case "refunded" -> PaymentState.REFUNDED;
            case "failed" -> PaymentState.FAILED;
            default -> PaymentState.CREATED;
        };
    }

    private static RefundState mapRefundState(String providerStatus) {
        if (providerStatus == null) {
            return RefundState.PENDING;
        }
        return switch (providerStatus) {
            case "processed" -> RefundState.PROCESSED;
            case "failed" -> RefundState.FAILED;
            default -> RefundState.PENDING;
        };
    }

    private static TransferState mapTransferState(String providerStatus) {
        if (providerStatus == null) {
            return TransferState.CREATED;
        }
        return switch (providerStatus) {
            case "pending" -> TransferState.PENDING;
            case "processed" -> TransferState.PROCESSED;
            case "failed" -> TransferState.FAILED;
            case "reversed" -> TransferState.REVERSED;
            default -> TransferState.CREATED;
        };
    }
}
