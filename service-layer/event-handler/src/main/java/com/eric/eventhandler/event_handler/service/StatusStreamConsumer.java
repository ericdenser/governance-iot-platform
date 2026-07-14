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

    private final ObjectMapper objectMapper;
    private final EventLogRepository eventLogRepository;
    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory redisConnectionFactory; 
    private final EventManagerService eventManagerService;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private Subscription subscription;
    private String consumerName;


    public StatusStreamConsumer(EventLogRepository eventLogRepository, RedisConnectionFactory redisConnectionFactory, StringRedisTemplate redisTemplate, EventManagerService eventManagerService, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.eventLogRepository = eventLogRepository;
        this.redisTemplate = redisTemplate;
        this.redisConnectionFactory = redisConnectionFactory;
        this.eventManagerService = eventManagerService;

    }
    
    @PostConstruct
    private void start() {
        createGroupIfMissing();
        consumerName = buildConsumerName();

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

        String payload = record.getValue().get("payload");
        try {
            StatusDTO dto = objectMapper.readValue(payload, StatusDTO.class);
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

    private String buildConsumerName() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
        } catch (Exception e) {
            return "consumer-" + UUID.randomUUID();
        }
    }

    @PreDestroy
    public void stop() {
        if (subscription != null) subscription.cancel();
        if (container != null) container.stop();
    }
}
