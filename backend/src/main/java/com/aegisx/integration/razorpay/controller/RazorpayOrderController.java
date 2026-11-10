package com.aegisx.integration.razorpay.controller;

import com.aegisx.common.dto.ApiResponse;
import com.aegisx.common.exception.TransactionNotFoundException;
import com.aegisx.integration.razorpay.dto.CreatePaymentOrderResponse;
import com.aegisx.integration.razorpay.service.RazorpayOrderClient.RazorpayOrderProviderException;
import com.aegisx.integration.razorpay.service.RazorpayOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class RazorpayOrderController {

    private final RazorpayOrderService razorpayOrderService;
    private final Environment environment;

    @GetMapping("/configuration")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getConfiguration() {
        Map<String, Object> configuration = Map.of(
                "checkoutEnabled", razorpayOrderService.isCheckoutConfigured(),
                "localTestEnabled", environment.acceptsProfiles(Profiles.of("dev")));
        return ResponseEntity.ok(ApiResponse.success(configuration, "Payment configuration retrieved"));
    }

    @PostMapping("/orders")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<CreatePaymentOrderResponse>> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            Authentication authentication) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                    razorpayOrderService.createOrder(request.transactionId(), authentication.getName()),
                    "Payment order created"));
        } catch (TransactionNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.getMessage()));
        } catch (RazorpayOrderProviderException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.error(e.getMessage()));
        }
    }

    public record CreateOrderRequest(@NotNull UUID transactionId) {
    }
}
