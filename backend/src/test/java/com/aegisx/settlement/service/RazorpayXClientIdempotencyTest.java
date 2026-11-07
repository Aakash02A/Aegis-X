package com.aegisx.settlement.service;

import com.aegisx.settlement.config.RazorpayXProperties;
import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.settlement.entity.Settlement;
import com.aegisx.settlement.entity.SettlementStatus;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayXClientIdempotencyTest {

    @Test
    void repeatsTheSamePayoutIdempotencyKeyAcrossRetries() throws Exception {
        List<String> idempotencyKeys = new CopyOnWriteArrayList<>();
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            idempotencyKeys.add(exchange.getRequestHeaders().getFirst("X-Payout-Idempotency"));
            exchange.getRequestBody().readAllBytes();
            byte[] response = ("{\"id\":\"pout_test_existing\",\"status\":\"queued\","
                    + "\"reference_id\":\"STL-TEST-IDEMPOTENT\",\"amount\":1000,\"currency\":\"INR\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();

        try {
            RazorpayXProperties properties = new RazorpayXProperties();
            properties.setEnabled(true);
            properties.setKeyId("rzp_test_fake-key-id");
            properties.setKeySecret("fake-test-secret");
            properties.setAccountNumber("fake-account-number");
            properties.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            RazorpayXClient client = new RazorpayXClient(properties, new ObjectMapper());
            ObjectNode metadata = new ObjectMapper().createObjectNode();
            metadata.put("fundAccountId", "fa_test_fake");
            Settlement settlement = Settlement.builder()
                    .transaction(Transaction.builder().status(TransactionStatus.APPROVED).build())
                    .settlementReference("STL-TEST-IDEMPOTENT")
                    .paymentProcessor(PaymentProcessor.RAZORPAYX)
                    .status(SettlementStatus.INITIATED)
                    .settlementAmount(new java.math.BigDecimal("10.00"))
                    .settlementCurrency("INR")
                    .processorMetadata(metadata)
                    .retryCount(0)
                    .build();
            settlement.setId(UUID.randomUUID());

            assertThat(client.submitPayout(settlement).isSuccess()).isTrue();
            settlement.setRetryCount(1);
            assertThat(client.submitPayout(settlement).isSuccess()).isTrue();

            assertThat(idempotencyKeys).hasSize(2).allMatch(key -> key != null && !key.isBlank());
            assertThat(idempotencyKeys.get(0)).isEqualTo(idempotencyKeys.get(1));
        } finally {
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }
}
