package com.eric.eventhandler.event_handler.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.eric.eventhandler.event_handler.model.dto.StatusDTO;
import com.eric.eventhandler.event_handler.repository.EventLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.lettuce.core.RedisBusyException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class StatusStreamConsumer {
    
    private static final String STREAM = "stream:status";
    private static final String GROUP = "eventhandler-group";

    private static final int SWEEP_BATCH = 100;
    private static final long MAX_DELIVERIES = 5;
    private static final Duration SWEEP_MIN_IDLE = Duration.ofSeconds(60);

    private final ObjectMapper objectMapper;
    private final EventLogRepository eventLogRepository;
    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory redisConnectionFactory;
    private final EventManagerService eventManagerService;

    private final String consumerName;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private Subscription subscription;


    public StatusStreamConsumer(EventLogRepository eventLogRepository, RedisConnectionFactory redisConnectionFactory, StringRedisTemplate redisTemplate, EventManagerService eventManagerService, ObjectMapper objectMapper,
                                @Value("${app.streams.replica-id:0}") String replicaId) {
        this.objectMapper = objectMapper;
        this.eventLogRepository = eventLogRepository;
        this.redisTemplate = redisTemplate;
        this.redisConnectionFactory = redisConnectionFactory;
        this.eventManagerService = eventManagerService;
        this.consumerName = "eventhandler-status-" + replicaId;
    }

    @PostConstruct
    private void start() {
        createGroupIfMissing();

        var options = StreamMessageListenerContainer
                    .StreamMessageListenerContainerOptions.builder()
                    .pollTimeout(Duration.ofMillis(1000))
                    .batchSize(100)
                    .build();

        container = StreamMessageListenerContainer.create(redisConnectionFactory, options);

        subscription = container.receive(
            Consumer.from(GROUP, consumerName),
            StreamOffset.create(STREAM, ReadOffset.lastConsumed()),
            this::handleRecord);
        
        container.start();
        log.info("StatusStreamConsumer initiated: stream={} group={} consumer={}", STREAM, GROUP, consumerName);
    }

    private void handleRecord(MapRecord<String, String, String> record) {
        String messageId = record.getId().getValue();

        if (eventLogRepository.existsBySourceMessageId(messageId)) {
            log.debug("Msg {} already processed, skipping", messageId);
            redisTemplate.opsForStream().acknowledge(GROUP, record);
            return;
        }

        // Erro permanente (payload inválido) — retentar nunca vai dar certo,
        // então ACK direto pra mensagem não voltar via sweep.
        StatusDTO dto;
        try {
            dto = objectMapper.readValue(record.getValue().get("payload"), StatusDTO.class);
        } catch (JsonProcessingException e) {
            log.warn("Payload status inválido, descartando id={}: {}", messageId, e.getMessage());
            redisTemplate.opsForStream().acknowledge(GROUP, record);
            return;
        }
        if (dto.deviceId() == null || dto.deviceId().isBlank()) {
            log.warn("Status sem deviceId (formato legado?), descartando id={} mac={}", messageId, dto.mac());
            redisTemplate.opsForStream().acknowledge(GROUP, record);
            return;
        }

        // erro transitorio, sweep reentrega
        try {
            eventManagerService.handleStatus(dto, messageId);
            redisTemplate.opsForStream().acknowledge(GROUP, record);
        } catch (Exception e) {
            log.error("Failed processing status id={}: {}", messageId, e.getMessage());
        }
    }

    private void createGroupIfMissing() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM, ReadOffset.from("0"), GROUP);
            log.info("Consumer group {} created in {}", GROUP, STREAM);
        } catch (RedisSystemException e) {
            if (e.getCause() instanceof RedisBusyException) {
                log.debug("Consumer group {} already exists in {}", GROUP, STREAM);
            } else {
                log.warn("Erro ao criar consumer group: {}", e.getMessage());
            }
        }
    }


    @Scheduled(fixedDelayString = "${app.streams.sweep-interval-ms:60000}", initialDelay = 15000)
    public void sweepPendingMessages() {
        try {
            StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
            PendingMessages pending = ops.pending(STREAM, GROUP, Range.unbounded(), SWEEP_BATCH);
            if (pending == null || pending.isEmpty()) return;

            List<RecordId> toClaim = new ArrayList<>();
            for (PendingMessage pm : pending) {
                if (pm.getElapsedTimeSinceLastDelivery().compareTo(SWEEP_MIN_IDLE) < 0) continue;
                if (pm.getTotalDeliveryCount() > MAX_DELIVERIES) {
                    log.error("Msg {} descartada após {} entregas (poison)", pm.getId(), pm.getTotalDeliveryCount());
                    ops.acknowledge(STREAM, GROUP, pm.getId());
                    continue;
                }
                toClaim.add(pm.getId());
            }
            if (toClaim.isEmpty()) return;

            List<MapRecord<String, String, String>> claimed = ops.claim(STREAM, GROUP, consumerName,
                    XClaimOptions.minIdle(SWEEP_MIN_IDLE).ids(toClaim));

            // id pedido mas não retornado = entrada já trimada do stream (MAXLEN) — ACK limpa o PEL
            Set<RecordId> returned = new HashSet<>();
            for (MapRecord<String, String, String> record : claimed) {
                returned.add(record.getId());
            }
            for (RecordId id : toClaim) {
                if (!returned.contains(id)) ops.acknowledge(STREAM, GROUP, id);
            }

            claimed.forEach(this::handleRecord);
            if (!claimed.isEmpty()) {
                log.info("PEL sweep: {} mensagens reivindicadas e reprocessadas em {}", claimed.size(), STREAM);
            }
        } catch (Exception e) {
            log.warn("PEL sweep falhou em {}: {}", STREAM, e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (subscription != null) subscription.cancel();
        if (container != null) container.stop();
    }
}
