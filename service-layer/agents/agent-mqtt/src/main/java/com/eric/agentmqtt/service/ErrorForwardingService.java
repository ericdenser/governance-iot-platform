package com.eric.agentmqtt.service;

import org.springframework.stereotype.Service;

import com.eric.agentmqtt.model.ErrorDTO;

import lombok.extern.slf4j.Slf4j;

/**
 * Publica DeviceError em stream:error (Redis).
 * Antes: POST /error/ingest no govApi. Migrado para Redis Streams no Obj 11 (Fase B).
 *
 * Nota: contrato (docs/REDIS_STREAMS_CONTRACT.md) atribui esse stream ao eventhandler-group.
 * O consumer na Fase C decide o destino final (event-handler pode processar direto ou
 * reencaminhar para govApi via HTTP interno).
 */
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
