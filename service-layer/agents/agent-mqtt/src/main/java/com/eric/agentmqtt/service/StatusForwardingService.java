package com.eric.agentmqtt.service;

import org.springframework.stereotype.Service;

import com.eric.agentmqtt.model.StatusDTO;

import lombok.extern.slf4j.Slf4j;

// Publica DeviceStatus em stream:status (Redis).

@Service
@Slf4j
public class StatusForwardingService {

    private final RedisStreamPublisher publisher;

    public StatusForwardingService(RedisStreamPublisher publisher) {
        this.publisher = publisher;
    }

    public void fowardStatusToEventHandler(StatusDTO dto) {
        log.debug("XADD stream:status device={} status={}", dto.deviceId(), dto.status());
        publisher.publish(RedisStreamPublisher.STREAM_STATUS, dto.deviceId(), "STATUS", dto);
    }
}
