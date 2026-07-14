package com.eric.datalogger.service;

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
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.eric.datalogger.model.TelemetryDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.lettuce.core.RedisBusyException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Service
@Slf4j
public class TelemetryStreamConsumer {
    
    private static final String STREAM = "stream:telemetry";
    private static final String GROUP = "datalogger-group";

    private static final int SWEEP_BATCH = 100;
    private static final long MAX_DELIVERIES = 5;
    private static final Duration SWEEP_MIN_IDLE = Duration.ofSeconds(60);

    private static final int FLUSH_MAX_POINTS = 500;

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final InfluxService influxService;
    private final ObjectMapper objectMapper;

    private final String consumerName;

    // Buffer do micro-batch: acumula pontos e ACKs até o flush.
    private final Object bufferLock = new Object();
    private final List<TelemetryDTO> bufferDtos = new ArrayList<>();
    private final List<RecordId> bufferIds = new ArrayList<>();

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private Subscription subscription;

    public TelemetryStreamConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate,
                                    InfluxService influxService, ObjectMapper objectMapper,
                                    @Value("${app.streams.replica-id:0}") String replicaId) {

            this.connectionFactory = connectionFactory;
            this.redisTemplate = redisTemplate;
            this.influxService = influxService;
            this.objectMapper = objectMapper;
            this.consumerName = "datalogger-" + replicaId;
    }

    @PostConstruct
    public void start() {
        createGroupIfMissing();

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
        // Erro permanente (payload inválido) — ACK direto pra não voltar via sweep.
        TelemetryDTO dto;
        try {
            dto = objectMapper.readValue(record.getValue().get("payload"), TelemetryDTO.class);
        } catch (JsonProcessingException e) {
            log.warn("Payload telemetry inválido, descartando id={}: {}", record.getId(), e.getMessage());
            redisTemplate.opsForStream().acknowledge(GROUP, record);
            return;
        }
        if (dto.deviceId() == null || dto.deviceId().isBlank()) {
            log.warn("Telemetry sem deviceId, descartando id={}", record.getId());
            redisTemplate.opsForStream().acknowledge(GROUP, record);
            return;
        }

        // Micro-batch: acumula e grava em lote (1 request HTTP por flush).
        // O ACK só acontece após o flush bem-sucedido — se o Influx cair,
        // as mensagens ficam no PEL e o sweep reentrega.
        synchronized (bufferLock) {
            bufferDtos.add(dto);
            bufferIds.add(record.getId());
            if (bufferDtos.size() >= FLUSH_MAX_POINTS) {
                flushLocked();
            }
        }
    }

    // Drena batches parciais quando o tráfego é baixo 
    @Scheduled(fixedDelay = 1000)
    public void flushBuffered() {
        synchronized (bufferLock) {
            if (!bufferDtos.isEmpty()) flushLocked();
        }
    }

    private void flushLocked() {
        try {
            influxService.writeTelemetryBatch(bufferDtos);
            redisTemplate.opsForStream()
                    .acknowledge(STREAM, GROUP, bufferIds.toArray(RecordId[]::new));
        } catch (Exception e) {
            // Sem ACK: o lote inteiro continua no PEL e o sweep reentrega.
            // Reescrita no Influx é idempotente (mesmo timestamp+tag = overwrite).
            log.error("Falha gravando batch de {} pontos no Influx: {}", bufferDtos.size(), e.getMessage());
        } finally {
            bufferDtos.clear();
            bufferIds.clear();
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
        // drena o que sobrou no buffer; se falhar, o PEL cobre no próximo boot
        flushBuffered();
    }


    
}
