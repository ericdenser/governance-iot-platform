package com.eric.agentmqtt.service;

import com.eric.agent.proto.DeviceError;
import com.eric.agent.proto.DeviceStatus;
import com.eric.agent.proto.DeviceTelemetry;
import com.eric.agent.proto.SensorReading;
import com.eric.agentmqtt.model.ErrorDTO;
import com.eric.agentmqtt.model.StatusDTO;
import com.eric.agentmqtt.model.TelemetryDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Service;

import javax.net.ssl.*;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class MqttAgent {

    private final StatusForwardingService statusForwardingService;
    private final ErrorForwardingService errorForwardingService;
    private final TelemetryForwardingService telemetryForwardingService;
    private final OAuth2AuthorizedClientManager auth2AuthorizedClientManager;

    public MqttAgent(StatusForwardingService statusForwardingService,
                     ErrorForwardingService errorForwardingService,
                     TelemetryForwardingService telemetryForwardingService, OAuth2AuthorizedClientManager auth2AuthorizedClientManager) {
        this.statusForwardingService = statusForwardingService;
        this.errorForwardingService = errorForwardingService;
        this.telemetryForwardingService = telemetryForwardingService;
        this.auth2AuthorizedClientManager = auth2AuthorizedClientManager;
    }

    @Value("${mqtt.broker-url}")
    private String brokerUrl;

    @Value("${mqtt.client-id}")
    private String clientId;

    @Value("${mqtt.topic}")
    private String topic;

    @Value("${mqtt.error-topic}")
    private String errorTopic;

    @Value("${mqtt.telemetry-topic}")
    private String telemetryTopic;

    @Value("${mqtt.password}") 
    private String mqttDummyPassword;

    private MqttClient client;
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, Object> parseDetail(String detail) {
        if (detail == null || detail.isBlank()) return null;
        try {
            return MAPPER.readValue(detail, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("detail", detail);
        }
    }

    private String fetchJwt() {
        OAuth2AuthorizeRequest req = OAuth2AuthorizeRequest
                    .withClientRegistrationId("govapi")
                    .principal("agent-mqtt")
                    .build();
        OAuth2AuthorizedClient auth = auth2AuthorizedClientManager.authorize(req);
        if (auth == null) {
            throw new IllegalStateException("Falha ao obter JWT do Keycloak (client 'govapi' não autorizado");
        }
        return auth.getAccessToken().getTokenValue();
    }

    @PostConstruct
    public void init() {
        log.info("Inicializando Agent MQTT (Protobuf mTLS)...");
        try {
            client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    log.error("Conexão com o Broker perdida: {}. Reconectando...",
                            cause != null ? cause.getMessage() : "Desconhecida");
                    startConnectionRoutine();
                }



                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    byte[] payload = message.getPayload();
                    log.info("Mensagem recebida em [{}]: {} bytes", topic, payload.length);

                    try {
                        if (topic.startsWith("status/")) {
                            String deviceId = topic.substring(topic.indexOf("/") + 1);
                            DeviceStatus proto = DeviceStatus.parseFrom(payload);
                            Instant ts = proto.getTimestamp() > 0
                                ? Instant.ofEpochSecond(proto.getTimestamp())
                                : Instant.now();
                            StatusDTO dto = new StatusDTO(
                                deviceId,
                                proto.getMac(),
                                proto.getFwVersion(),
                                proto.getSsid(),
                                String.valueOf(proto.getState()),
                                parseDetail(proto.getDetail()),
                                ts,
                                proto.getActiveSensors()
                                
                            );
                            log.info("{}", dto);
                            statusForwardingService.fowardStatusToEventHandler(dto);

                        } else if (topic.startsWith("error/")) {
                            DeviceError err = DeviceError.parseFrom(payload);
                            Instant ts = err.getTimestamp() > 0
                                ? Instant.ofEpochSecond(err.getTimestamp())
                                : Instant.now();
                            ErrorDTO dto = new ErrorDTO(
                                err.getDeviceId(),
                                err.getMac(),
                                err.getFwVersion(),
                                err.getSsid(),
                                String.valueOf(err.getErrorCode()),
                                err.getErrorMsg(),
                                err.getErrorSource(),
                                err.getExtra().isEmpty() ? null : err.getExtra(),
                                err.getResolved(),
                                ts
                            );
                            log.info("{}", dto);
                            errorForwardingService.fowardErrorToEventHandler(dto);

                        } else if (topic.startsWith("telemetry/")) {
                            DeviceTelemetry proto = DeviceTelemetry.parseFrom(payload);
                            Instant ts = proto.getTimestamp() > 0
                                ? Instant.ofEpochSecond(proto.getTimestamp())
                                : Instant.now();
                            Map<String, Float> readings = new LinkedHashMap<>();
                            for (SensorReading r : proto.getReadingsList()) {
                                readings.put(r.getKey(), r.getValue());
                            }
                            TelemetryDTO dto = new TelemetryDTO(proto.getDeviceId(), readings, ts);
                            log.info("{}", dto);
                            telemetryForwardingService.forwardTelemetryToDataLogger(dto);
                        }

                    } catch (Exception e) {
                        log.warn("Falha ao decodificar payload protobuf (tópico={}): {}", topic, e.getMessage());
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            startConnectionRoutine();

        } catch (MqttException e) {
            log.error("Erro fatal ao instanciar Agent MQTT: {}", e.getMessage(), e);
        }
    }

    private void startConnectionRoutine() {
        if (isConnecting.compareAndSet(false, true)) {
            new Thread(this::connectWithRetry, "MqttConnectionThread").start();
        }
    }

    private void connectWithRetry() {
        while (client != null && !client.isConnected()) {
            try {
                log.info("Obtendo JWT do Keycloak e conectando em {}...", brokerUrl);
                MqttConnectOptions options = new MqttConnectOptions();
                options.setCleanSession(true);
                options.setConnectionTimeout(10);
                options.setAutomaticReconnect(false);
                options.setUserName(fetchJwt());
                options.setPassword(mqttDummyPassword.toCharArray());
                client.connect(options);
                log.info("Conectado. Inscrevendo em [{}, {}, {}]", topic, errorTopic, telemetryTopic);
                client.subscribe(new String[]{topic, errorTopic, telemetryTopic}, new int[]{1, 1, 0});
                isConnecting.set(false);
                return;
            } catch (Exception e) {
                log.error("Falha ao conectar no MQTT: {}. Nova tentativa em 10s...", e.getMessage());
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    isConnecting.set(false);
                    return;
                }
            }
        }
        isConnecting.set(false);
    }

    public void publish(String topic, String payload, int qos, boolean retained) {
        if (client == null || !client.isConnected()) {
            log.error("Cliente MQTT não conectado — impossível publicar em [{}]", topic);
            throw new IllegalStateException("MQTT client not connected");
        }
        try {
            MqttMessage msg = new MqttMessage(payload.getBytes());
            msg.setQos(qos);
            msg.setRetained(retained);
            client.publish(topic, msg);
            log.info("Publicado em [{}]: {}", topic, payload);
        } catch (MqttException e) {
            log.error("Falha ao publicar em [{}]: {}", topic, e.getMessage());
            throw new RuntimeException("Falha MQTT publish", e);
        }
    }

   
    @Scheduled(fixedRateString = "${mqtt.jwt-refresh-interval-ms:1800000}")
    public void refreshJwtAndReconnect() {
        if (client == null || !client.isConnected()) {
            return;   // sem conexão ativa, nada a refrescar
        }
        log.info("Refresh preventivo de JWT — desconectando pra pegar token novo no reconnect.");
        try {
            client.disconnect();

            startConnectionRoutine();
        } catch (MqttException e) {
            log.warn("Falha ao desconectar pra refresh: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
                log.info("MQTT client desconectado.");
            }
        } catch (MqttException e) {
            log.warn("Erro ao desconectar: {}", e.getMessage());
        }
    }
}
