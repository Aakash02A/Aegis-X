package com.aegisx.integration.razorpay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RazorpayWebhookPayload {

    @JsonProperty("entity")
    private String entity;

    @JsonProperty("account_id")
    private String accountId;

    @JsonProperty("event")
    private String event;

    @JsonProperty("created_at")
    private Long createdAt;

    @JsonProperty("payload")
    private PayloadContainer payload;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PayloadContainer {

        @JsonProperty("payment")
        private PaymentWrapper payment;

        @JsonProperty("order")
        private OrderWrapper order;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PaymentWrapper {

        @JsonProperty("entity")
        private PaymentEntity entity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderWrapper {

        @JsonProperty("entity")
        private OrderEntity entity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderEntity {

        @JsonProperty("id")
        private String id;

        @JsonProperty("receipt")
        private String receipt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PaymentEntity {

        @JsonProperty("id")
        private String id;

        @JsonProperty("amount")
        private BigDecimal amount;

        @JsonProperty("currency")
        private String currency;

        @JsonProperty("status")
        private String status;

        @JsonProperty("order_id")
        private String orderId;

        @JsonProperty("notes")
        private Map<String, String> notes;

        @JsonProperty("error_code")
        private String errorCode;

        @JsonProperty("error_description")
        private String errorDescription;

        @JsonProperty("error_reason")
        private String errorReason;
    }

    public String getPaymentId() {
        if (payload != null && payload.getPayment() != null && payload.getPayment().getEntity() != null) {
            return payload.getPayment().getEntity().getId();
        }
        return null;
    }

    public String getOrderId() {
        if (payload != null && payload.getPayment() != null && payload.getPayment().getEntity() != null) {
            return payload.getPayment().getEntity().getOrderId();
        }
        if (payload != null && payload.getOrder() != null && payload.getOrder().getEntity() != null) {
            return payload.getOrder().getEntity().getId();
        }
        return null;
    }

    public BigDecimal getAmount() {
        return payload != null && payload.getPayment() != null && payload.getPayment().getEntity() != null
                ? payload.getPayment().getEntity().getAmount() : null;
    }

    public String getCurrency() {
        return payload != null && payload.getPayment() != null && payload.getPayment().getEntity() != null
                ? payload.getPayment().getEntity().getCurrency() : null;
    }

    public String getTransactionReferenceFromNotes() {
        if (payload != null && payload.getPayment() != null && payload.getPayment().getEntity() != null) {
            Map<String, String> notes = payload.getPayment().getEntity().getNotes();
            if (notes != null) {
                if (notes.containsKey("transaction_reference")) {
                    return notes.get("transaction_reference");
                }
                if (notes.containsKey("reference_number")) {
                    return notes.get("reference_number");
                }
            }
        }
        return null;
    }
}
