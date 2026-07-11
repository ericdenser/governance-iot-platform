package com.eric.governanceApi.governanceApi.exceptions;

import com.eric.governanceApi.governanceApi.enums.ErrorCode;

import lombok.Getter;

@Getter
public class InfrastructureException extends RuntimeException {
    private final ErrorCode errorCode;

    public InfrastructureException(String message) {
        super(message);
        this.errorCode = ErrorCode.INFRASTRUCTURE_UNAVAILABLE;
    }

    public InfrastructureException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public InfrastructureException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = ErrorCode.INFRASTRUCTURE_UNAVAILABLE;
    }

    public InfrastructureException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
