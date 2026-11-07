package com.aegisx.integrity;

import com.aegisx.integrity.config.IntegrityProperties;
import com.aegisx.integrity.domain.CapabilityStatus;
import com.aegisx.integrity.provider.PaymentProviderAdapter;
import com.aegisx.integrity.provider.RazorpayTestAdapter;
import com.aegisx.integrity.webhook.IntegrityWebhookSignatureService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayTestAdapterModeTest {

    @Test
    void refusesLiveAndMalformedTestKeyIdsBeforeAnyProviderRequest() {
        for (String keyId : new String[]{"rzp_live_fake", "rzp_testmalformed"}) {
            IntegrityProperties properties = new IntegrityProperties();
            properties.getRazorpay().setBaseUrl("http://127.0.0.1:1");
            properties.getRazorpay().setKeyId(keyId);
            properties.getRazorpay().setKeySecret("fake-test-secret");
            RazorpayTestAdapter adapter = new RazorpayTestAdapter(properties,
                    new IntegrityWebhookSignatureService(), new ObjectMapper());

            var outcome = adapter.createOrder(new PaymentProviderAdapter.CreateOrderCommand(
                    100L, "INR", "test-reference", "offline test"));

            assertThat(outcome.status()).isEqualTo(CapabilityStatus.NOT_CONFIGURED);
            assertThat(outcome.detail()).contains("non-test key id");
        }
    }
}
