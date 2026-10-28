package com.aegisx.notification;

import com.aegisx.integration.whatsapp.WhatsAppWebhookService;
import com.aegisx.notification.dto.DeliveryStatus;
import com.aegisx.notification.dto.Direction;
import com.aegisx.notification.dto.WhatsAppWebhookPayload;
import com.aegisx.notification.entity.NotificationMessage;
import com.aegisx.notification.repository.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("WhatsApp integration")
class WhatsAppIntegrationTest {

    @Autowired private NotificationRepository notificationRepository;
    @Autowired private WhatsAppWebhookService webhookService;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("verifies the configured webhook token")
    void testWebhookTokenVerification() {
        assertThat(webhookService.verifyWebhookToken("test-webhook-token")).isTrue();
        assertThat(webhookService.verifyWebhookToken("wrong-token")).isFalse();
        assertThat(webhookService.verifyWebhookToken(null)).isFalse();
    }

    @Test
    @DisplayName("classifies an empty payload as unknown and a real message payload")
    void testEventTypeClassification() throws Exception {
        assertThat(webhookService.classifyEventType(null)).isEqualTo("UNKNOWN");
        WhatsAppWebhookPayload messagePayload = objectMapper.readValue("""
                {"object":"whatsapp_business_account","entry":[{"changes":[{"value":{
                  "messages":[{"from":"919820011234","id":"wamid.integration-1","type":"text"}]
                }}]}]}
                """, WhatsAppWebhookPayload.class);

        assertThat(webhookService.classifyEventType(messagePayload)).isEqualTo("MESSAGE");
        assertThat(webhookService.extractSenderPhone(messagePayload)).isEqualTo("919820011234");
        assertThat(webhookService.extractMessageId(messagePayload)).isEqualTo("wamid.integration-1");
    }

    @Test
    @DisplayName("persists outbound notification and supports message-ID idempotency lookup")
    void testNotificationPersistenceAndIdempotencyLookup() {
        UUID transactionId = UUID.randomUUID();
        NotificationMessage notification = NotificationMessage.builder()
                .transactionId(transactionId)
                .channel("WHATSAPP")
                .direction(Direction.OUTBOUND)
                .recipient("+1234567890")
                .templateName("document_request")
                .messageId("wamid.integration-" + UUID.randomUUID())
                .status(DeliveryStatus.SENT)
                .build();
        notification.setCreatedAt(Instant.now());
        notification.setUpdatedAt(Instant.now());
        notification = notificationRepository.saveAndFlush(notification);

        NotificationMessage saved = notificationRepository.findByMessageId(notification.getMessageId())
                .orElseThrow();

        assertThat(saved.getId()).isEqualTo(notification.getId());
        assertThat(saved.getTransactionId()).isEqualTo(transactionId);
        assertThat(saved.getDirection()).isEqualTo(Direction.OUTBOUND);
        assertThat(saved.getStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    @DisplayName("persists delivery status updates against the existing notification")
    void testDeliveryStatusTracking() {
        NotificationMessage message = NotificationMessage.builder()
                .transactionId(UUID.randomUUID())
                .channel("WHATSAPP")
                .direction(Direction.OUTBOUND)
                .recipient("+1234567890")
                .messageId("wamid.delivery-" + UUID.randomUUID())
                .status(DeliveryStatus.SENT)
                .build();
        message.setCreatedAt(Instant.now());
        message.setUpdatedAt(Instant.now());
        message = notificationRepository.saveAndFlush(message);
        message.setStatus(DeliveryStatus.DELIVERED);
        message.setUpdatedAt(Instant.now());
        notificationRepository.saveAndFlush(message);

        assertThat(notificationRepository.findById(message.getId()).orElseThrow().getStatus())
                .isEqualTo(DeliveryStatus.DELIVERED);
    }
}
