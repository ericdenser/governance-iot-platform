package com.eric.agentgrpc.service;

import com.eric.agent.grpc.datalogger.TestMessage;
import com.eric.agent.grpc.messaging.Message;
import com.eric.agent.grpc.messaging.MessagingGrpc;
import com.eric.agent.grpc.messaging.RegisterRequest;
import com.eric.agentgrpc.model.ErrorDTO;
import com.eric.agentgrpc.model.StatusDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class GrpcSubscriberService {

    private final MessagingGrpc.MessagingStub messagingStub;
    private final StatusForwardingService statusForwardingService;
    private final ErrorForwardingService errorForwardingService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean running = new AtomicBoolean(true);

    @Value("${grpc.broker.topic}")
    private String topic;

    @Value("${grpc.broker.error-topic}")
    private String errorTopic;

    public GrpcSubscriberService(MessagingGrpc.MessagingStub messagingStub,
                                  StatusForwardingService statusForwardingService,
                                  ErrorForwardingService errorForwardingService) {
        this.messagingStub = messagingStub;
        this.statusForwardingService = statusForwardingService;
        this.errorForwardingService = errorForwardingService;
    }

    @PostConstruct
    public void connect() {
        subscribe(topic);
        subscribe(errorTopic);
    }

    private void subscribe(String t) {
        log.info("Conectando ao broker gRPC, inscrevendo no tópico [{}]...", t);
        RegisterRequest request = RegisterRequest.newBuilder().setTopic(t).build();

        messagingStub.subscribe(request, new StreamObserver<>() {
            @Override
            public void onNext(Message msg) {
                String rawMessage = msg.getMessage();
                int rawSizeBytes  = rawMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

                log.info("Mensagem recebida — tópico: [{}], origem: [{}] | Base64 na rede: {} bytes",
                         msg.getTopic(), msg.getFrom(), rawSizeBytes);

                try {
                    if (t.equals(errorTopic)) {
                        ErrorDTO err = decodeErrorMessage(rawMessage);
                        if (err != null) {
                            log.info("ErrorDTO: {}", err);
                            errorForwardingService.fowardErrorToEventHandler(err);
                        }
                    } else {
                        StatusDTO dto = decodeMessage(rawMessage);
                        if (dto != null) {
                            log.info("StatusDTO: {}", dto);
                            statusForwardingService.fowardStatusToEventHandler(dto);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Erro ao decodificar mensagem [{}]: {}", t, e.getMessage());
                }
            }

            @Override
            public void onError(Throwable th) {
                log.error("Erro na stream [{}]: {}. Reconectando em 5s...", t, th.getMessage());
                scheduleReconnect(t);
            }

            @Override
            public void onCompleted() {
                log.warn("Stream [{}] encerrada pelo servidor. Reconectando em 5s...", t);
                scheduleReconnect(t);
            }
        });
    }

    // Flow: Base64 → protobuf TestMessage → JSON text → StatusDTO
    // Fallback: plain JSON (test payloads that skip protobuf wrapping)
    // Non-JSON/non-protobuf payloads (e.g. MAC addresses sent by broker on connect) return null and are skipped.
    private StatusDTO decodeMessage(String rawMessage) throws Exception {
        // Try Base64 + protobuf first
        try {
            byte[] protoBytes = Base64.getDecoder().decode(rawMessage);
            log.debug("Payload protobuf: {} bytes", protoBytes.length);
            TestMessage testMessage = TestMessage.parseFrom(protoBytes);
            return objectMapper.readValue(testMessage.getText(), StatusDTO.class);
        } catch (Exception ignored) {
            // not a valid Base64+protobuf payload — fall through
        }

        // Try plain JSON
        String trimmed = rawMessage.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            log.debug("Payload JSON puro: {} bytes", rawMessage.length());
            return objectMapper.readValue(rawMessage, StatusDTO.class);
        }

        // Unrecognised format (e.g. broker sends MAC address on session restore)
        log.debug("Mensagem ignorada (formato desconhecido, {} bytes): {}", rawMessage.length(), rawMessage);
        return null;
    }

    private ErrorDTO decodeErrorMessage(String rawMessage) throws Exception {
        String trimmed = rawMessage.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return objectMapper.readValue(rawMessage, ErrorDTO.class);
        }
        log.debug("Mensagem de erro ignorada (formato desconhecido, {} bytes): {}", rawMessage.length(), rawMessage);
        return null;
    }

    private void scheduleReconnect(String t) {
        if (running.get()) {
            scheduler.schedule(() -> subscribe(t), 5, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        scheduler.shutdownNow();
    }
}
