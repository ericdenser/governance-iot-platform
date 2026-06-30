package com.eric.governanceApi.governanceApi.service.EventHandlers;

import com.eric.governanceApi.governanceApi.enums.EventType;
import com.eric.governanceApi.governanceApi.model.request.DeviceEventWebhookDTO;

public interface DeviceEventHandler {
    
    EventType handles();
    void process(DeviceEventWebhookDTO event);
}
