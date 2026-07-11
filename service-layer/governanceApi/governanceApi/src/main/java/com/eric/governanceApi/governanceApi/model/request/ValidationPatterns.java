package com.eric.governanceApi.governanceApi.model.request;

public final class ValidationPatterns {
    public static final String UUID_REGEX =
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
    public static final String UUID_MESSAGE = "deve ser um UUID válido";

    // Provisioning token = UUID sem hífens (32 chars hex).
    // Gerado por ProvisioningToken.java: UUID.randomUUID().toString().replace("-", "")
    public static final String PROVISIONING_TOKEN_REGEX = "^[0-9a-fA-F]{32}$";
    public static final String PROVISIONING_TOKEN_MESSAGE = "deve ter 32 caracteres hexadecimais (UUID sem hífens)";

    private ValidationPatterns() {}
}
