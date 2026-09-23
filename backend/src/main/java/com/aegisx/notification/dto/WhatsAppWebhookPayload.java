package com.aegisx.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * WhatsAppWebhookPayload represents the Meta WhatsApp webhook event structure.
 * 
 * Webhook types:
 * - Message event: User sends message or media
 * - Delivery receipt: Message delivered/read status
 * - Message template event: Outbound template sent
 * 
 * Note: Only process events with valid verification token.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppWebhookPayload {
    
    @JsonProperty("object")
    private String object;
    
    @JsonProperty("entry")
    private List<Entry> entries;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entry {
        private String id;
        private Long timestamp;
        private List<Change> changes;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Change {
        private String field;
        private Value value;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Value {
        
        @JsonProperty("messaging_product")
        private String messagingProduct;
        
        @JsonProperty("metadata")
        private Metadata metadata;
        
        @JsonProperty("messages")
        private List<Message> messages;
        
        @JsonProperty("statuses")
        private List<Status> statuses;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Metadata {
        @JsonProperty("display_phone_number")
        private String displayPhoneNumber;
        
        @JsonProperty("phone_number_id")
        private String phoneNumberId;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String id;
        private String from;
        private Long timestamp;
        
        @JsonProperty("type")
        private String type;
        
        private Text text;
        private Image image;
        private Document document;
        private Video video;
        private Audio audio;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Text {
        @JsonProperty("body")
        private String body;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Image {
        private String id;
        private String mime_type;
        private String sha256;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Document {
        private String id;
        private String mime_type;
        private String sha256;
        private String filename;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Video {
        private String id;
        private String mime_type;
        private String sha256;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Audio {
        private String id;
        private String mime_type;
        private String sha256;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Status {
        private String id;
        private String status;
        private Long timestamp;
        
        @JsonProperty("recipient_id")
        private String recipientId;
        
        private Map<String, Object> errors;
    }
}
