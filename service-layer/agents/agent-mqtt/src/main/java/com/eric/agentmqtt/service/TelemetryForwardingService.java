package com.eric.agentmqtt.service;

import org.springframework.stereotype.Service;

import com.eric.agentmqtt.model.TelemetryDTO;

import lombok.extern.slf4j.Slf4j;


@Service
@Slf4j
public class TelemetryForwardingService {

    private final RedisStreamPublisher publisher;

    public TelemetryForwardingService(RedisStreamPublisher publisher) {
        this.publisher = publisher;
    }

    public void forwardTelemetryToDataLogger(TelemetryDTO dto) {
        log.debug("XADD stream:telemetry device={} readings={}",
                dto.deviceId(), dto.readings().keySet());
        publisher.publish(RedisStreamPublisher.STREAM_TELEMETRY, dto.deviceId(), "TELEMETRY", dto);
    }
}
