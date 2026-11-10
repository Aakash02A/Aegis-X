package com.aegisx.integration.razorpay.service;

import com.aegisx.integration.razorpay.config.RazorpayProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RazorpayOrderClientTest {

    @Test
    void refusesLiveOrMalformedKeyIdsBeforeOrderCreation() {
        for (String keyId : new String[]{"rzp_live_fake", "rzp_testmalformed"}) {
            RazorpayProperties properties = new RazorpayProperties();
            properties.setKeyId(keyId);
            properties.setKeySecret("fake-test-secret");
            properties.setApiBaseUrl("http://127.0.0.1:1");
            RazorpayOrderClient client = new RazorpayOrderClient(properties);

            assertThat(properties.isConfigured()).isFalse();
            assertThatThrownBy(() -> client.createOrder(100L, "INR", "test-reference", java.util.Map.of()))
                    .isInstanceOf(RazorpayOrderClient.RazorpayOrderProviderException.class)
                    .hasMessageContaining("not configured");
        }

        RazorpayProperties testProperties = new RazorpayProperties();
        testProperties.setKeyId("rzp_test_fake-key-id");
        testProperties.setKeySecret("fake-test-secret");
        assertThat(testProperties.isConfigured()).isTrue();
    }
}
