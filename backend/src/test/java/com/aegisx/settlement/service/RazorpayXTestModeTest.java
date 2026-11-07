package com.aegisx.settlement.service;

import com.aegisx.settlement.config.RazorpayXProperties;
import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.settlement.entity.Settlement;
import com.aegisx.settlement.entity.SettlementStatus;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RazorpayXTestModeTest {

    @Test
    void acceptsOnlyTestModeKeyIdsAndRefusesLiveKeyBeforeNetworkAccess() {
        RazorpayXProperties testProperties = configuredProperties("rzp_test_fake-key-id");
        RazorpayXClient testClient = new RazorpayXClient(testProperties, new ObjectMapper());
        assertThat(testClient.isConfigured()).isTrue();

        RazorpayXProperties liveProperties = configuredProperties("rzp_live_fake-key-id");
        liveProperties.setApiBaseUrl("http://127.0.0.1:1");
        RazorpayXClient liveClient = new RazorpayXClient(liveProperties, new ObjectMapper());
        Settlement settlement = Settlement.builder()
                .transaction(Transaction.builder().status(TransactionStatus.APPROVED).build())
                .settlementReference("STL-TEST-ONLY")
                .paymentProcessor(PaymentProcessor.RAZORPAYX)
                .status(SettlementStatus.INITIATED)
                .settlementAmount(new BigDecimal("10.00"))
                .settlementCurrency("INR")
                .processorMetadata(metadataWithFundAccount())
                .retryCount(0)
                .build();

        assertThat(liveClient.isConfigured()).isFalse();
        assertThatThrownBy(() -> liveClient.submitPayout(settlement))
                .isInstanceOf(RazorpayXClient.RazorpayXProviderException.class)
                .hasMessageContaining("disabled or not fully configured");
    }

    private static RazorpayXProperties configuredProperties(String keyId) {
        RazorpayXProperties properties = new RazorpayXProperties();
        properties.setEnabled(true);
        properties.setKeyId(keyId);
        properties.setKeySecret("fake-test-secret");
        properties.setAccountNumber("fake-account-number");
        return properties;
    }

    private static ObjectNode metadataWithFundAccount() {
        ObjectNode metadata = new ObjectMapper().createObjectNode();
        metadata.put("fundAccountId", "fa_test_fake");
        return metadata;
    }
}
