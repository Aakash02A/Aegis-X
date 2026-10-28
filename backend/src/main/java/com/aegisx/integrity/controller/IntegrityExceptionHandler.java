package com.aegisx.integrity.controller;

import com.aegisx.common.exception.InvalidWebhookSignatureException;
import com.aegisx.common.response.ApiResponse;
import com.aegisx.integrity.exception.FinancialExecutionDisabledException;
import com.aegisx.integrity.exception.IllegalPaymentStateException;
import com.aegisx.integrity.exception.IntegrityConflictException;
import com.aegisx.integrity.exception.IntegrityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Error mapping for the integrity module only.
 *
 * <p>Scoped with {@code basePackages} so it cannot alter the behaviour of the existing compliance
 * endpoints. Messages are deliberately plain: they describe what was rejected and why, and never
 * echo an exception message that could contain a provider payload or a credential.</p>
 *
 * <p>The explicit precedence matters. {@link com.aegisx.common.exception.GlobalExceptionHandler} is
 * an unscoped advice whose {@code @ExceptionHandler(Exception.class)} catch-all will otherwise win
 * for any exception this class declares, turning a genuine 404 or 409 from the integrity module into
 * a 500 with an internal message. Taking precedence for this module's controllers keeps an
 * unreadable document distinct from a server fault, which is exactly the distinction an operator
 * needs when deciding whether to retry or to investigate.</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.aegisx.integrity")
public class IntegrityExceptionHandler {

    @ExceptionHandler(IntegrityNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(IntegrityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(IntegrityConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(IntegrityConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(IllegalPaymentStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalPaymentStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(FinancialExecutionDisabledException.class)
    public ResponseEntity<ApiResponse<Void>> handleFinancialDisabled(FinancialExecutionDisabledException e) {
        log.warn("Blocked an attempt to execute a financially consequential recovery action");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(InvalidWebhookSignatureException.class)
    public ResponseEntity<ApiResponse<Void>> handleSignature(InvalidWebhookSignatureException e) {
        // 401 tells the provider the delivery was not authentic. The body is intentionally generic.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Webhook signature verification failed"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Invalid request: " + e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Validation failed: " + detail));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException e) {
        log.error("Integrity module misconfiguration or internal error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("The integrity engine could not complete the request"));
    }
}
