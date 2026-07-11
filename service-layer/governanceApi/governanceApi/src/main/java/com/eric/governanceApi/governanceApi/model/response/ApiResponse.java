package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.eric.governanceApi.governanceApi.enums.ErrorCode;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ApiResponse<T> {
    private boolean success;
    private T data;
    private ApiError error;
    private Instant timestamp;
    private String traceId;
    private String path;


    public static <T> ApiResponse<T> success(T data, String path) {
        return ApiResponse.<T>builder()
            .success(true)
            .data(data)
            .error(null)
            .timestamp(Instant.now())
            .traceId(generateTraceId())
            .path(path)
            .build();
    }

    public static <T> ApiResponse<T> error(String code, String message, String path) {
        return errorInternal(new ApiError(code, message, null), path);
    }

    public static <T> ApiResponse<T> error(ErrorCode code, String message, String path) {
        return errorInternal(new ApiError(code.name(), message, null), path);
    }

    public static <T> ApiResponse<T> error(ErrorCode code, String message, Map<String, String> fieldErrors, String path) {
        return errorInternal(new ApiError(code.name(), message, fieldErrors), path);
    }

    private static <T> ApiResponse<T> errorInternal(ApiError error, String path) {
        return ApiResponse.<T>builder()
            .success(false)
            .data(null)
            .error(error)
            .timestamp(Instant.now())
            .traceId(generateTraceId())
            .path(path)
            .build();
    }

    private static String generateTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
