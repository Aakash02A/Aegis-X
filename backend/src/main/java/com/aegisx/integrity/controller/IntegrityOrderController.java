package com.aegisx.integrity.controller;

import com.aegisx.common.response.ApiResponse;
import com.aegisx.integrity.domain.CapabilityStatus;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.PaymentRecord;
import com.aegisx.integrity.entity.ProviderObservation;
import com.aegisx.integrity.entity.RefundRecord;
import com.aegisx.integrity.entity.StateTransitionRecord;
import com.aegisx.integrity.entity.TransferRecord;
import com.aegisx.integrity.security.ActorContext;
import com.aegisx.integrity.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Module B endpoints: the order and payment registry. */
@Slf4j
@RestController
@RequestMapping("/api/integrity/workspaces/{workspaceId}")
@RequiredArgsConstructor
public class IntegrityOrderController {

    private final OrderService orderService;
    private final ActorContext actorContext;

    public record CreateOrderRequest(
            @NotNull @Min(1) Long amountMinor,
            @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be a 3-letter ISO-4217 code") String currency,
            @Size(max = 255) String description) { }

    public record CreateOrderResponse(UUID orderId,
                                      String internalReference,
                                      String providerOrderId,
                                      String providerStatus,
                                      String note,
                                      boolean simulated,
                                      @NotNull String currency,
                                      long expectedAmountMinor) { }

    public record OrderDetailResponse(PaymentOrder order,
                                      List<PaymentRecord> payments,
                                      List<RefundRecord> refunds,
                                      List<TransferRecord> transfers,
                                      List<ProviderObservation> observations,
                                      List<StateTransitionRecord> transitions) { }

    @PostMapping("/orders")
    @PreAuthorize("hasAnyRole('ADMIN','MERCHANT','COMPLIANCE_OFFICER')")
    public ResponseEntity<ApiResponse<CreateOrderResponse>> createOrder(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateOrderRequest request,
            Authentication authentication) {
        ActorContext.Actor actor = actorContext.resolve(authentication);
        OrderService.CreationOutcome outcome = orderService.create(workspaceId, request.amountMinor(),
                request.currency(), request.description(), actor.userId());
        PaymentOrder order = outcome.order();
        CreateOrderResponse response = new CreateOrderResponse(order.getId(), order.getInternalReference(),
                order.getProviderOrderId(), outcome.providerStatus().name(), outcome.note(), order.isSimulated(),
                order.getCurrency(), order.getExpectedAmountMinor());
        String message = outcome.providerStatus() == CapabilityStatus.NOT_CONFIGURED
                ? "Internal order recorded; provider order was not created because the provider is not configured"
                : "Order created";
        return ResponseEntity.ok(ApiResponse.success(response, message));
    }

    @GetMapping("/orders")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<PaymentOrder>>> listOrders(@PathVariable UUID workspaceId) {
        return ResponseEntity.ok(ApiResponse.success(orderService.list(workspaceId), "Orders retrieved"));
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<OrderDetailResponse>> orderDetail(@PathVariable UUID workspaceId,
                                                                       @PathVariable UUID orderId) {
        OrderService.OrderDetail detail = orderService.detail(workspaceId, orderId);
        return ResponseEntity.ok(ApiResponse.success(new OrderDetailResponse(detail.order(), detail.payments(),
                detail.refunds(), detail.transfers(), detail.observations(), detail.transitions()),
                "Order detail retrieved"));
    }

    @GetMapping("/payments")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<PaymentRecord>>> listPayments(@PathVariable UUID workspaceId) {
        return ResponseEntity.ok(ApiResponse.success(orderService.listPayments(workspaceId), "Payments retrieved"));
    }

    @GetMapping("/refunds")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<RefundRecord>>> listRefunds(@PathVariable UUID workspaceId) {
        return ResponseEntity.ok(ApiResponse.success(orderService.listRefunds(workspaceId), "Refunds retrieved"));
    }

    @GetMapping("/transfers")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<TransferRecord>>> listTransfers(@PathVariable UUID workspaceId) {
        return ResponseEntity.ok(ApiResponse.success(orderService.listTransfers(workspaceId), "Transfers retrieved"));
    }
}
