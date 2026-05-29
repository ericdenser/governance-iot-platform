package com.eric.governanceApi.governanceApi.model.dto;

// DTO enviado pelo event-handler da info do device que disparou o evento 
public record DeviceInfoDTO(

    String mac,
    String firmware_version,
    String ssid,
    String status
) {
    

}
