package com.eric.agentmqtt.service;

import org.springframework.stereotype.Service;

import com.eric.agentmqtt.model.ErrorDTO;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ErrorForwardingService {

    private final RedisStreamPublisher publisher;

    public ErrorForwardingService(RedisStreamPublisher publisher) {
        this.publisher = publisher;
    }

    public void fowardErrorToEventHandler(ErrorDTO dto) {
        log.debug("XADD stream:error device={} code={}", dto.device_id(), dto.errorCode());
        publisher.publish(RedisStreamPublisher.STREAM_ERROR, dto.device_id(), "ERROR", dto);
    }
}
