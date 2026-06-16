package com.eric.agent.service;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eric.agent.model.ErrorDTO;
import com.eric.agent.model.StatusDTO;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class MqttAgent {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StatusForwardingService statusForwardingService;
    private final ErrorForwardingService errorForwardingService;

    public MqttAgent(StatusForwardingService statusForwardingService,
                     ErrorForwardingService errorForwardingService) {
        this.statusForwardingService = statusForwardingService;
        this.errorForwardingService = errorForwardingService;
    }

    @Value("${mqtt.broker-url}")
    private String brokerUrl;

    @Value("${mqtt.client-id}")
    private String clientId;

    @Value("${mqtt.topic}")
    private String topic;

    @Value("${mqtt.error-topic}")
    private String errorTopic;

    @Value("${mqtt.keystore-path}")
    private Resource keystoreResource;

    @Value("${mqtt.keystore-password}")
    private String keystorePassword;

    @Value("${mqtt.truststore-path}")
    private Resource truststoreResource;

    @Value("${mqtt.truststore-password}")
    private String truststorePassword;

    private MqttClient client;
    
    // Controle para garantir que apenas uma Thread tente conectar por vez
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        log.info("Inicializando configuração do MQTT...");
        try {
            client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    log.error("Conexão com o Broker perdida: {}. Iniciando rotina de reconexão...", 
                            cause != null ? cause.getMessage() : "Desconhecida");
                    startConnectionRoutine();
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload());
                    log.info("Mensagem recebida em [{}]: {}", topic, payload);

                    try {
                        if (topic.startsWith("status/")) {
                            StatusDTO dto = objectMapper.readValue(payload, StatusDTO.class);
                            statusForwardingService.fowardStatusToEventHandler(dto);

                        } else if (topic.startsWith("error/")) {
                            ErrorDTO dto = objectMapper.readValue(payload, ErrorDTO.class);
                            errorForwardingService.fowardErrorToEventHandler(dto);
                        }
                    } catch (Exception e) {
                        log.warn("Erro ao processar mensagem (tópico={}): {}", topic, e.getMessage());
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            // Inicia a primeira tentativa de conexão sem travar o boot do Spring
            startConnectionRoutine();

        } catch (MqttException e) {
            log.error("Erro fatal ao instanciar Agent MQTT: {}", e.getMessage(), e);
        }
    }

    /**
     * Inicia a thread de conexão apenas se não houver outra rodando.
     */
    private void startConnectionRoutine() {
        if (isConnecting.compareAndSet(false, true)) {
            new Thread(this::connectWithRetry, "MqttConnectionThread").start();
        }
    }

    /**
     * Loop infinito de tentativas com 1 minuto de intervalo.
     */
    private void connectWithRetry() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        // Controle manual de reconexão para forçar o delay exato de 1 minuto
        options.setAutomaticReconnect(false); 

        try {
            options.setSocketFactory(getSocketFactory());
            options.setHttpsHostnameVerificationEnabled(false);
        } catch (Exception e) {
            log.error("Falha ao carregar certificados SSL. Impossível conectar ao MQTT: {}", e.getMessage());
            isConnecting.set(false);
            return;
        }

        while (client != null && !client.isConnected()) {
            try {
                log.info("Tentando conectar ao Broker MQTT em {}...", brokerUrl);
                client.connect(options);
                
                log.info("Conectado com sucesso! Inscrevendo nos tópicos [{}, {}]", topic, errorTopic);
                client.subscribe(new String[]{topic, errorTopic}, new int[]{1, 1});
                
                isConnecting.set(false); // Libera o lock
                return; // Sai do loop e encerra a thread
                
            } catch (Exception e) {
                log.error("Falha ao conectar no MQTT: {}. Nova tentativa em 1 minuto...", e.getMessage());
                try {
                    Thread.sleep(120000); // 120 segundos exatos
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Thread de conexão MQTT interrompida.");
                    isConnecting.set(false);
                    return;
                }
            }
        }
        isConnecting.set(false);
    }

    //  Publish 
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

    private SSLSocketFactory getSocketFactory() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = keystoreResource.getInputStream()) {
            keyStore.load(is, keystorePassword.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keystorePassword.toCharArray());

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = truststoreResource.getInputStream()) {
            trustStore.load(is, truststorePassword.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new java.security.SecureRandom());
        return sslContext.getSocketFactory();
    }
}