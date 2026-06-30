package com.eric.governanceApi.governanceApi.model.request;


public record RegisterDeviceRequest(
    String deviceName,
    String groupId
) {}
