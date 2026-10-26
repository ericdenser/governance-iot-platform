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
    // Teto de lotes por sweep ao escoar o backlog depois de uma queda do Influx
    private static final int SWEEP_MAX_ROUNDS = 50;

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final InfluxService influxService;
    private final ObjectMapper objectMapper;

    private final String consumerName;

    // Buffer do micro-batch: acumula pontos e ACKs até o flush.
    private final Object bufferLock = new Object();
    private final List<TelemetryDTO> bufferDtos = new ArrayList<>();
    private final List<RecordId> bufferIds = new ArrayList<>();

    private volatile boolean influxDown = false;

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
            log.warn("Payload telemetry invalid, discarding id={}: {}", record.getId(), e.getMessage());
            redisTemplate.opsForStream().acknowledge(GROUP, record);
            return;
        }
        if (dto.deviceId() == null || dto.deviceId().isBlank()) {
            log.warn("Telemetry sem deviceId, descartando id={}", record.getId());
            redisTemplate.opsForStream().acknowledge(GROUP, record);
            return;
        }

        // Influx fora: sem ACK, a msg fica no PEL e o sweep reentrega quando voltar
        if (influxDown) return;

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
            acknowledge(bufferIds);
            markUp();
        } catch (InfluxUnavailableException e) {
            // Sem ACK: o lote continua no PEL. Reescrita no Influx e idempotente
            // (mesmo timestamp+tag = overwrite).
            markDown(e.getMessage());
        } catch (Exception e) {
            // Um ponto invalido derruba o lote inteiro: grava um a um
            log.warn("Lote de {} pontos recusado pelo Influx, gravando um a um: {}", bufferDtos.size(), e.getMessage());
            writeOneByOne();
        } finally {
            bufferDtos.clear();
            bufferIds.clear();
        }
    }

    private void writeOneByOne() {
        List<RecordId> done = new ArrayList<>();
        try {
            for (int i = 0; i < bufferDtos.size(); i++) {
                TelemetryDTO dto = bufferDtos.get(i);
                try {
                    influxService.writeTelemetryBatch(List.of(dto));
                } catch (InfluxUnavailableException e) {
                    // caiu no meio: o que falta fica no PEL
                    markDown(e.getMessage());
                    return;
                } catch (Exception e) {
                    log.error("Ponto invalido descartado id={} device={}: {}", bufferIds.get(i), dto.deviceId(), e.getMessage());
                }
                done.add(bufferIds.get(i));
            }
        } finally {
            acknowledge(done);
        }
    }

    private void acknowledge(List<RecordId> ids) {
        if (ids.isEmpty()) return;
        redisTemplate.opsForStream().acknowledge(STREAM, GROUP, ids.toArray(RecordId[]::new));
    }

    // Loga so na transicao, nao a cada mensagem
    private void markDown(String reason) {
        if (!influxDown) log.error("Influx indisponivel, telemetria fica no stream ate voltar: {}", reason);
        influxDown = true;
    }

    private void markUp() {
        if (influxDown) log.info("Influx voltou, escoando telemetria pendente");
        influxDown = false;
    }

    @Scheduled(fixedDelay = 10000)
    public void probeInflux() {
        if (influxDown && influxService.isUp()) markUp();
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
        // Com o Influx fora, reentregar so somaria entregas ate virar poison
        if (influxDown) return;
        try {
            int total = 0;
            for (int round = 0; round < SWEEP_MAX_ROUNDS && !influxDown; round++) {
                int claimed = sweepRound();
                if (claimed == 0) break;
                total += claimed;
                flushBuffered();
            }
            if (total > 0) {
                log.info("PEL sweep: {} mensagens reivindicadas e reprocessadas em {}", total, STREAM);
            }
        } catch (Exception e) {
            log.warn("PEL sweep falhou em {}: {}", STREAM, e.getMessage());
        }
    }

    // Um lote do PEL. Retorna quantas entradas saíram do PEL ou foram reprocessadas.
    private int sweepRound() {
        StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
        PendingMessages pending = ops.pending(STREAM, GROUP, Range.unbounded(), SWEEP_BATCH);
        if (pending == null || pending.isEmpty()) return 0;

        int poisoned = 0;
        List<RecordId> toClaim = new ArrayList<>();
        for (PendingMessage pm : pending) {
            if (pm.getElapsedTimeSinceLastDelivery().compareTo(SWEEP_MIN_IDLE) < 0) continue;
            if (pm.getTotalDeliveryCount() > MAX_DELIVERIES) {
                log.error("Msg {} descartada após {} entregas (poison)", pm.getId(), pm.getTotalDeliveryCount());
                ops.acknowledge(STREAM, GROUP, pm.getId());
                poisoned++;
                continue;
            }
            toClaim.add(pm.getId());
        }
        if (toClaim.isEmpty()) return poisoned;

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
        return poisoned + toClaim.size();
    }

    @PreDestroy
    public void stop() {
        if (subscription != null) subscription.cancel();
        if (container != null) container.stop();
        // drena o que sobrou no buffer; se falhar, o PEL cobre no próximo boot
        flushBuffered();
    }


    
}
