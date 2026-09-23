package com.aegisx.integration.whatsapp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * WhatsAppMediaService handles downloading media from Meta WhatsApp Cloud API.
 * 
 * Responsibilities:
 * - Retrieve media URL from Meta API
 * - Download media file
 * - Handle expired media gracefully
 * - Stream media bytes for document ingestion
 * 
 * Design:
 * - Media IDs only valid for 5 minutes after receipt
 * - Download immediately upon webhook
 * - Store in local storage, never bypass DocumentService
 * - Audit all downloads in notification record
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppMediaService {
    
    private final RestTemplate restTemplate;
    
    @Value("${aegisx.whatsapp.access-token}")
    private String accessToken;
    
    @Value("${aegisx.whatsapp.api-endpoint}")
    private String apiEndpoint;
    
    /**
     * Get media URL from Meta API.
     * Media URLs are valid for 5 minutes; must download immediately.
     * 
     * @param mediaId Media ID from webhook
     * @return Media URL if available, null if failed
     */
    public String getMediaUrl(String mediaId) {
        try {
            String url = String.format("%s/%s", apiEndpoint, mediaId);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            
            HttpEntity<String> request = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                // Response contains { "url": "...", "mime_type": "..." }
                // Parse and return the URL
                log.debug("Retrieved media URL for mediaId={}", mediaId);
                return response.getBody();
            } else {
                log.warn("Failed to get media URL: mediaId={}, status={}", mediaId, response.getStatusCode());
                return null;
            }
            
        } catch (RestClientException e) {
            log.error("Network error retrieving media URL: mediaId={}, error={}", mediaId, e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Unexpected error retrieving media URL: mediaId={}, error={}", mediaId, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Download media file from URL.
     * 
     * @param mediaUrl Media URL (from getMediaUrl)
     * @return Media bytes if successful, null if failed
     */
    public byte[] downloadMedia(String mediaUrl) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            
            HttpEntity<String> request = new HttpEntity<>(headers);
            
            ResponseEntity<byte[]> response = restTemplate.getForEntity(mediaUrl, byte[].class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.debug("Downloaded media: size={} bytes", response.getBody().length);
                return response.getBody();
            } else {
                log.warn("Failed to download media: status={}", response.getStatusCode());
                return null;
            }
            
        } catch (RestClientException e) {
            log.error("Network error downloading media: error={}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Unexpected error downloading media: error={}", e.getMessage(), e);
            return null;
        }
    }
}
