package com.aegisx.integration.razorpay.service;

import com.aegisx.integration.razorpay.config.RazorpayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

@Slf4j
@Component
public class RazorpayOrderClient {

    private final RazorpayProperties properties;
    private final RestClient restClient;

    public RazorpayOrderClient(RazorpayProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getApiBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public JsonNode findOrdersByReceipt(String receipt) {
        ensureConfigured();
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/orders")
                            .queryParam("receipt", receipt)
                            .queryParam("count", 100)
                            .build())
                    .headers(headers -> headers.setBasicAuth(properties.getKeyId(), properties.getKeySecret()))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new RazorpayOrderProviderException("Razorpay order lookup failed (HTTP "
                    + e.getStatusCode().value() + ")");
        } catch (ResourceAccessException e) {
            throw new RazorpayOrderProviderException("Razorpay Orders API is unavailable");
        }
    }

    public JsonNode createOrder(long amountMinor, String currency, String receipt, Map<String, String> notes) {
        ensureConfigured();
        try {
            return restClient.post()
                    .uri("/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBasicAuth(properties.getKeyId(), properties.getKeySecret()))
                    .body(Map.of(
                            "amount", amountMinor,
                            "currency", currency,
                            "receipt", receipt,
                            "partial_payment", false,
                            "notes", notes))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new RazorpayOrderProviderException("Razorpay order creation failed (HTTP "
                    + e.getStatusCode().value() + ")");
        } catch (ResourceAccessException e) {
            throw new RazorpayOrderProviderException("Razorpay Orders API is unavailable");
        }
    }

    private void ensureConfigured() {
        if (!properties.isConfigured()) {
            throw new RazorpayOrderProviderException("Razorpay Orders API is not configured");
        }
    }

    public static class RazorpayOrderProviderException extends RuntimeException {
        public RazorpayOrderProviderException(String message) {
            super(message);
        }
    }
}
