package com.eric.datalogger.service;

import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;

import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.eric.datalogger.model.TelemetryDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.lettuce.core.RedisBusyException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Service
@Slf4j
public class TelemetryStreamConsumer {
    
    private static final String STREAM = "stream:telemetry";
    private static final String GROUP = "datalogger-group";

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final InfluxService influxService;
    private final ObjectMapper objectMapper;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private Subscription subscription;
    private String consumerName;

    public TelemetryStreamConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate,
                                    InfluxService influxService, ObjectMapper objectMapper) {

            this.connectionFactory = connectionFactory;
            this.redisTemplate = redisTemplate;
            this.influxService = influxService;
            this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        createGroupIfMissing();
        consumerName = buildConsumerName();

        var options = StreamMessageListenerContainer
                    .StreamMessageListenerContainerOptions.builder()
                    .pollTimeout(Duration.ofMillis(1000))
                    .batchSize(100)
                    .build();

        container = StreamMessageListenerContainer.create(connectionFactory, options);
        
        subscription = container.receive(
                    Consumer.from(GROUP, consumerName), 
                    StreamOffset.create(STREAM, ReadOffset.lastConsumed()),
                    this::handleRecord);

        container.start();
        log.info("TelemetryStreamConsumer initiated: stream={} group={} consumer={}", STREAM, GROUP, consumerName);
    }

    private void handleRecord(MapRecord<String, String, String> record) {
        String payload = record.getValue().get("payload");
        try {
            TelemetryDTO dto = objectMapper.readValue(payload, TelemetryDTO.class);
            influxService.writeTelemetry(dto);
            redisTemplate.opsForStream().acknowledge(GROUP, record);
        } catch (Exception e) {
            log.error("Falha processando telemetry id={}: {}", record.getId(), e.getMessage());
        }
    }

    private void createGroupIfMissing() {
        // SOLUCAO encontrada no Medium para criar stream caso nao exista, legado
        // if (!redisTemplate.hasKey(STREAM)) {
        //     RedisAsyncCommands commands = (RedisAsyncCommands) redisTemplate
        //                                                     .getConnectionFactory()
        //                                                     .getConnection()
        //                                                     .getNativeConnection();
        //     CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
        //                             .add(CommandKeyword.CREATE)
        //                             .add(STREAM)
        //                             .add(GROUP)
        //                             .add("0")
        //                             .add("MKSTREAM");
        //     commands.dispatch(CommandType.XGROUP, new StatusOutput<>(StringCodec.UTF8), args);
        // } else {
            try {
                // comando createGroup ja cria stream caso ainda nao exista
                redisTemplate.opsForStream().createGroup(STREAM, ReadOffset.from("0"), GROUP);
                log.info("Consumer group criado: {}", GROUP);
            } catch (RedisSystemException e) {
                if (e.getCause() instanceof RedisBusyException) {
                    log.debug("Consumer group {} ja existe", GROUP);
                } else {
                    log.warn("Erro ao criar consumer group: {}", e.getMessage());
                }
            }
        
    }

    // escalavel, cada datalogger tem um name unico, redis balanceia e entrega entre elas
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
