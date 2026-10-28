package com.aegisx.integrity.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Parses a Razorpay webhook body into {@link RazorpayEventPayload}.
 *
 * <p>The body shape is the documented one: a top-level {@code event} string, a {@code payload}
 * object containing resource envelopes such as {@code payment}/{@code order}/{@code refund}, each
 * with an {@code entity} object. The event id is <em>not</em> in the body — it arrives in the
 * {@code x-razorpay-event-id} header — so it is supplied by the caller.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RazorpayWebhookParser {

    private final ObjectMapper objectMapper;

    public RazorpayEventPayload parse(String rawBody, String providerEventId) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String eventType = text(root, "event");
            JsonNode payload = root.path("payload");

            JsonNode payment = entity(payload, "payment");
            JsonNode order = entity(payload, "order");
            JsonNode refund = entity(payload, "refund");
            JsonNode transfer = entity(payload, "transfer");

            return new RazorpayEventPayload(
                    providerEventId,
                    eventType == null ? "unknown" : eventType,
                    text(payment, "id"),
                    text(payment, "order_id") != null ? text(payment, "order_id") : text(order, "id"),
                    text(refund, "id"),
                    text(transfer, "id"),
                    firstNonNull(text(payment, "status"), text(refund, "status"), text(order, "status"),
                            text(transfer, "status")),
                    firstNonNullLong(longValue(payment, "amount"), longValue(refund, "amount"),
                            longValue(order, "amount"), longValue(transfer, "amount")),
                    firstNonNull(text(payment, "currency"), text(refund, "currency"), text(order, "currency")),
                    text(payment, "method"),
                    text(payment, "error_description"),
                    epochInstant(root, "created_at"),
                    firstNonNull(text(payment, "receipt"), text(order, "receipt")));
        } catch (Exception e) {
            log.warn("Unable to parse Razorpay webhook body: {}", e.getClass().getSimpleName());
            throw new IllegalArgumentException("Malformed webhook payload");
        }
    }

    private static JsonNode entity(JsonNode payload, String name) {
        return payload.path(name).path("entity");
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Long longValue(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }

    private static Instant epochInstant(JsonNode root, String field) {
        JsonNode value = root.path(field);
        return value.isNumber() ? Instant.ofEpochSecond(value.asLong()) : Instant.now();
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long firstNonNullLong(Long... values) {
        for (Long value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
