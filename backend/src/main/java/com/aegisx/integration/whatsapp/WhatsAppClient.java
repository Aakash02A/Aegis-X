package com.aegisx.integration.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * WhatsAppClient handles all outbound communication with Meta WhatsApp Cloud API.
 * 
 * Responsibilities:
 * - Send template messages (document requests)
 * - Handle API errors gracefully
 * - Parse message IDs for idempotency
 * - Implement retry logic at HTTP level
 * 
 * Design:
 * - All requests authenticated with access token
 * - Requests use v18.0 endpoint
 * - Sandbox mode supported for MVP
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppClient {
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    @Value("${aegisx.whatsapp.phone-number-id}")
    private String phoneNumberId;
    
    @Value("${aegisx.whatsapp.access-token}")
    private String accessToken;
    
    @Value("${aegisx.whatsapp.api-endpoint}")
    private String apiEndpoint;
    
    /**
     * Send a WhatsApp template message.
     * 
     * @param recipientPhoneNumber Recipient phone number (international format, no +)
     * @param templateName Template identifier (e.g., "document_request")
     * @param templateParams Template parameters (JSON object)
     * @return Message ID if successful, empty if failed
     */
    public String sendTemplateMessage(String recipientPhoneNumber, String templateName, JsonNode templateParams) {
        try {
            String url = String.format("%s/%s/messages", apiEndpoint, phoneNumberId);
            
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("messaging_product", "whatsapp");
            payload.put("recipient_type", "individual");
            payload.put("to", recipientPhoneNumber);
            payload.put("type", "template");
            
            ObjectNode template = objectMapper.createObjectNode();
            template.put("name", templateName);
            
            if (templateParams != null) {
                template.set("parameters", templateParams);
            }
            
            payload.set("template", template);
            
            log.debug("Sending WhatsApp template: phone={}, template={}", recipientPhoneNumber, templateName);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + accessToken);
            
            HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(payload), headers);
            
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode responseBody = objectMapper.readTree(response.getBody());
                String messageId = responseBody.get("messages").get(0).get("id").asText();
                log.info("WhatsApp message sent: id={}, template={}, phone={}", messageId, templateName, recipientPhoneNumber);
                return messageId;
            } else {
                log.error("Failed to send WhatsApp message: status={}, body={}", response.getStatusCode(), response.getBody());
                return null;
            }
            
        } catch (RestClientException e) {
            log.error("Network error sending WhatsApp message: phone={}, template={}, error={}", 
                recipientPhoneNumber, templateName, e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("Unexpected error sending WhatsApp message: phone={}, template={}, error={}", 
                recipientPhoneNumber, templateName, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Check if WhatsApp integration is configured.
     * 
     * @return true if credentials are configured
     */
    public boolean isConfigured() {
        return phoneNumberId != null && !phoneNumberId.isEmpty() &&
               accessToken != null && !accessToken.isEmpty();
    }
}
