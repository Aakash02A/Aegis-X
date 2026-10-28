package com.aegisx.integrity.controller;

import com.aegisx.common.response.ApiResponse;
import com.aegisx.integrity.entity.IntegrityWebhookEvent;
import com.aegisx.integrity.entity.WebhookDelivery;
import com.aegisx.integrity.repository.IntegrityWebhookEventRepository;
import com.aegisx.integrity.repository.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Webhook observability: unique events, every delivery attempt, and the duplicate count.
 *
 * <p>The distinction matters operationally. One row per <em>event</em> is the deduplication
 * guarantee; one row per <em>delivery</em> is the evidence that the provider retried. Raw payloads
 * are not included in the list response because they can contain more than an operator needs to
 * see in a table.</p>
 */
@RestController
@RequestMapping("/api/integrity/workspaces/{workspaceId}/webhooks")
@RequiredArgsConstructor
public class IntegrityWebhookHistoryController {

    private final IntegrityWebhookEventRepository eventRepository;
    private final WebhookDeliveryRepository deliveryRepository;

    public record WebhookHistory(List<IntegrityWebhookEvent> events,
                                 List<WebhookDelivery> deliveries,
                                 Map<String, Long> deliveriesByEvent,
                                 long duplicateDeliveryCount,
                                 long failedEventCount,
                                 String endpointTemplate) { }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<WebhookHistory>> history(@PathVariable UUID workspaceId) {
        List<IntegrityWebhookEvent> events = eventRepository.findAllByWorkspaceIdOrderByReceivedAtDesc(workspaceId);
        List<WebhookDelivery> deliveries = deliveryRepository.findAllByWorkspaceIdOrderByReceivedAtDesc(workspaceId);
        Map<String, Long> perEvent = deliveries.stream()
                .collect(Collectors.groupingBy(WebhookDelivery::getProviderEventId, LinkedHashMap::new, Collectors.counting()));
        long duplicates = deliveries.stream().filter(delivery -> !delivery.isFirstDelivery()).count();
        long failed = events.stream()
                .filter(event -> event.getProcessingState() == com.aegisx.integrity.domain.WebhookProcessingState.FAILED)
                .count();
        WebhookHistory body = new WebhookHistory(events, deliveries, perEvent, duplicates, failed,
                "/api/integrity/webhooks/razorpay/" + workspaceId);
        return ResponseEntity.ok(ApiResponse.success(body, "Webhook history retrieved"));
    }
}
