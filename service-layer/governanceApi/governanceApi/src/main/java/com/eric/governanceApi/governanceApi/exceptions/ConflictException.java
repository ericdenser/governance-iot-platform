package com.eric.governanceApi.governanceApi.exceptions;

import com.eric.governanceApi.governanceApi.enums.ErrorCode;

import lombok.Getter;

@Getter
public class ConflictException extends RuntimeException {
    private final ErrorCode errorCode;

    public ConflictException(String message) {
        super(message);
        this.errorCode = ErrorCode.CONFLICT;
    }

    public ConflictException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
