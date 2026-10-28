package com.aegisx.integration.whatsapp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signature verification is the only thing standing between an anonymous POST and the inbound
 * message pipeline, so it is tested against digests computed the way Meta computes them — not
 * against a digest produced by the implementation under test.
 */
@DisplayName("WhatsApp webhook signature verification")
class WhatsAppWebhookSignatureTest {

    private static final String APP_SECRET = "test-app-secret-2f7c9a";
    private static final String BODY =
            "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1\",\"changes\":[]}]}";

    private WhatsAppWebhookService service;

    @BeforeEach
    void setUp() {
        service = new WhatsAppWebhookService();
        ReflectionTestUtils.setField(service, "appSecret", APP_SECRET);
    }

    @Test
    @DisplayName("accepts the HMAC-SHA256 signature Meta sends in X-Hub-Signature-256")
    void acceptsMetaSha256Signature() throws Exception {
        String header = "sha256=" + hmacHex("HmacSHA256", APP_SECRET, BODY);

        assertThat(service.verifyWebhookSignature(BODY, header)).isTrue();
    }

    @Test
    @DisplayName("rejects plain SHA-256 over body+secret, which is not how HMAC works")
    void rejectsBodyPlusSecretDigest() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((BODY + APP_SECRET).getBytes(StandardCharsets.UTF_8));
        String header = "sha256=" + HexFormat.of().formatHex(digest);

        assertThat(service.verifyWebhookSignature(BODY, header)).isFalse();
    }

    @Test
    @DisplayName("accepts the legacy HMAC-SHA1 signature in X-Hub-Signature")
    void acceptsLegacySha1Signature() throws Exception {
        String header = "sha1=" + hmacHex("HmacSHA1", APP_SECRET, BODY);

        assertThat(service.verifyWebhookSignature(BODY, header)).isTrue();
    }

    @Test
    @DisplayName("rejects a signature computed over a different body")
    void rejectsTamperedBody() throws Exception {
        String header = "sha256=" + hmacHex("HmacSHA256", APP_SECRET, BODY);
        String tampered = BODY.replace("\"id\":\"1\"", "\"id\":\"2\"");

        assertThat(service.verifyWebhookSignature(tampered, header)).isFalse();
    }

    @Test
    @DisplayName("rejects a signature computed with a different app secret")
    void rejectsWrongSecret() throws Exception {
        String header = "sha256=" + hmacHex("HmacSHA256", "some-other-secret", BODY);

        assertThat(service.verifyWebhookSignature(BODY, header)).isFalse();
    }

    @Test
    @DisplayName("rejects a missing signature header")
    void rejectsMissingHeader() {
        assertThat(service.verifyWebhookSignature(BODY, null)).isFalse();
        assertThat(service.verifyWebhookSignature(BODY, "  ")).isFalse();
    }

    @Test
    @DisplayName("rejects an unsupported algorithm prefix")
    void rejectsUnknownAlgorithm() {
        assertThat(service.verifyWebhookSignature(BODY, "md5=d41d8cd98f00b204e9800998ecf8427e")).isFalse();
        assertThat(service.verifyWebhookSignature(BODY, "no-prefix-at-all")).isFalse();
    }

    @Test
    @DisplayName("rejects a malformed hex digest instead of throwing")
    void rejectsMalformedHex() {
        assertThat(service.verifyWebhookSignature(BODY, "sha256=zzzz")).isFalse();
    }

    @Test
    @DisplayName("rejects everything when the app secret is not configured")
    void rejectsWhenAppSecretMissing() throws Exception {
        ReflectionTestUtils.setField(service, "appSecret", "");
        String header = "sha256=" + hmacHex("HmacSHA256", APP_SECRET, BODY);

        assertThat(service.verifyWebhookSignature(BODY, header)).isFalse();
    }

    private static String hmacHex(String algorithm, String secret, String body) throws Exception {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
