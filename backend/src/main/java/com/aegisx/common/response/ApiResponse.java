package com.aegisx.common.response;

import com.aegisx.common.filter.CorrelationIdFilter;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

import java.time.Instant;

/** Compatibility package for controllers using the response namespace. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private boolean success;
    private T data;
    private String message;
    private Instant timestamp;
    private String correlationId;

    public ApiResponse() { }

    private ApiResponse(boolean success, T data, String message) {
        this.success = success;
        this.data = data;
        this.message = message;
        this.timestamp = Instant.now();
        this.correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY);
    }

    public static <T> ApiResponse<T> success(T data, String message) { return new ApiResponse<>(true, data, message); }
    public static <T> ApiResponse<T> success(T data) { return success(data, null); }
    public static <T> ApiResponse<T> error(String message) { return new ApiResponse<>(false, null, message); }
    public boolean isSuccess() { return success; }
    public T getData() { return data; }
    public String getMessage() { return message; }
    public Instant getTimestamp() { return timestamp; }
    public String getCorrelationId() { return correlationId; }
}
