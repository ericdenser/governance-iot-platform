package com.eric.governanceApi.governanceApi.model;

import java.time.Instant;
import java.util.UUID;

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
            .traceId(gerenateTraceid())
            .path(path)
            .build();
    }

    public static <T> ApiResponse<T> error(String code, String message, String path) {
        return ApiResponse.<T>builder()
            .success(false)
            .data(null)
            .error(new ApiError(code, message))
            .timestamp(Instant.now())
            .traceId(gerenateTraceid())
            .path(path)
            .build();
    }

    private static String gerenateTraceid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
