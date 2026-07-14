package com.eric.governanceApi.governanceApi.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.eric.governanceApi.governanceApi.model.request.DeviceErrorDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Consome stream:error do Redis e delega ao ErrorDispatcherService.
 *
 * Bypass do event-handler: erros iam agent-mqtt → event-handler HTTP → govApi.
 * Agora vão direto agent-mqtt → stream:error → govApi. Sem hop intermediário
 * porque event-handler não faz nada sobre erro além de reencaminhar.
 *
 * Idempotência via SET-NX com TTL — dispensa migration em ErrorRecord.
 * Trade-off: dedupe fica em memória Redis (perdido em restart do redis-streams).
 * Janela de 1h cobre replay de bug em consumer.
 */
@Service
@Slf4j
public class ErrorStreamConsumer {

    private static final String STREAM = "stream:error";
    private static final String GROUP = "errorhandler-group";
    private static final Duration DEDUPE_TTL = Duration.ofHours(1);

    private static final int SWEEP_BATCH = 100;
    private static final long MAX_DELIVERIES = 5;
    private static final Duration SWEEP_MIN_IDLE = Duration.ofSeconds(60);

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final ErrorDispatcherService errorDispatcherService;
    private final ObjectMapper objectMapper;

    // Estável entre restarts (sem PID) — herda o próprio PEL ao subir de novo.
    private final String consumerName;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private Subscription subscription;

    public ErrorStreamConsumer(RedisConnectionFactory connectionFactory,
                               StringRedisTemplate redisTemplate,
                               ErrorDispatcherService errorDispatcherService,
                               ObjectMapper objectMapper,
                               @Value("${app.streams.replica-id:0}") String replicaId) {
        this.connectionFactory = connectionFactory;
        this.redisTemplate = redisTemplate;
        this.errorDispatcherService = errorDispatcherService;
        this.objectMapper = objectMapper;
        this.consumerName = "govapi-error-" + replicaId;
    }

    @PostConstruct
    public void start() {
        createGroupIfMissing();

        var options = StreamMessageListenerContainer
                .StreamMessageListenerContainerOptions.builder()
                .pollTimeout(Duration.ofMillis(1000))
                .batchSize(50)
                .build();

        container = StreamMessageListenerContainer.create(connectionFactory, options);

        subscription = container.receive(
                Consumer.from(GROUP, consumerName),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()),
                this::handleRecord
        );

        container.start();
        log.info("ErrorStreamConsumer iniciado: stream={} group={} consumer={}",
                STREAM, GROUP, consumerName);
    }

    private void handleRecord(MapRecord<String, String, String> record) {
        String messageId = record.getId().getValue();

        // Dedupe via SET-NX: primeiro que consegue setar processa; resto ignora
        Boolean firstTime = redisTemplate.opsForValue()
                .setIfAbsent("dedupe:error:" + messageId, "1", DEDUPE_TTL);
        if (Boolean.FALSE.equals(firstTime)) {
            log.debug("Msg error {} já processada, skipping", messageId);
            redisTemplate.opsForStream().acknowledge(GROUP, record);
            return;
        }

        String payload = record.getValue().get("payload");
        try {
            DeviceErrorDTO dto = objectMapper.readValue(payload, DeviceErrorDTO.class);
            errorDispatcherService.dispatch(dto);
            redisTemplate.opsForStream().acknowledge(GROUP, record);
        } catch (Exception e) {
            log.error("Falha processando error id={}: {}", messageId, e.getMessage());
            // remove dedupe pra permitir reprocessar na próxima entrega
            redisTemplate.delete("dedupe:error:" + messageId);
        }
    }

    private void createGroupIfMissing() {
        try {
            redisTemplate.execute((RedisCallback<Object>) connection ->
                connection.execute("XGROUP",
                    "CREATE".getBytes(),
                    STREAM.getBytes(),
                    GROUP.getBytes(),
                    "0".getBytes(),
                    "MKSTREAM".getBytes()
                )
            );
            log.info("Consumer group criado (com MKSTREAM): {}", GROUP);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("BUSYGROUP")) {
                log.debug("Consumer group {} já existe", GROUP);
            } else {
                log.warn("Erro ao criar consumer group: {}", msg);
            }
        }
    }

    /**
     * Recupera mensagens presas no PEL (entregues sem ACK). O handler já trata
     * a reentrega: em falha ele deleta a chave de dedupe, então o reprocesso
     * via sweep passa pelo SET-NX de novo. Poison messages (> MAX_DELIVERIES)
     * são descartadas com ACK.
     */
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
