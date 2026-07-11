package com.eric.governanceApi.governanceApi.model.response;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {
    private String code;
    private String message;
    private Map<String, String> fieldErrors;

    public ApiError(String code, String message) {
        this.code = code;
        this.message = message;
    }
}
