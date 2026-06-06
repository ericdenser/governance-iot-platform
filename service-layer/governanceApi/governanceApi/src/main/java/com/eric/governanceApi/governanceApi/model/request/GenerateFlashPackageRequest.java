package com.eric.governanceApi.governanceApi.model.request;

public record GenerateFlashPackageRequest(
    String deviceName,
    String wifiSsid,
    String wifiPass
) {}
