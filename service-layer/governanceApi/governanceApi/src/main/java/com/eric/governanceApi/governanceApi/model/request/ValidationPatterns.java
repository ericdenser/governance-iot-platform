package com.eric.governanceApi.governanceApi.model.request;

public final class ValidationPatterns {
    public static final String UUID_REGEX =
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
    public static final String UUID_MESSAGE = "deve ser um UUID válido";

    private ValidationPatterns() {}
}
