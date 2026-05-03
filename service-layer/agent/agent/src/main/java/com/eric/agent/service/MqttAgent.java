package com.eric.agent.service;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eric.agent.model.DataDTO;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;

@Slf4j
@Service
public class MqttAgent {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataForwardingService dataForwardingService;

    public MqttAgent(DataForwardingService dataForwardingService) {
        this.dataForwardingService = dataForwardingService;
    }

    @Value("${mqtt.broker-url}")
    private String brokerUrl;

    @Value("${mqtt.client-id}")
    private String clientId;

    @Value("${mqtt.topic}")
    private String topic;

    @Value("${mqtt.keystore-path}")
    private Resource keystoreResource;

    @Value("${mqtt.keystore-password}")
    private String keystorePassword;

    @Value("${mqtt.truststore-path}")
    private Resource truststoreResource;

    @Value("${mqtt.truststore-password}")
    private String truststorePassword;

    private MqttClient client;

    @PostConstruct
    public void connect() {
        log.info("Iniciando conexão MQTT segura (mTLS)...");
        try {
            client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            options.setSocketFactory(getSocketFactory());
            options.setHttpsHostnameVerificationEnabled(false);

            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    log.error("Conexão com o Broker perdida: {}", cause.getMessage());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload());
                    log.info("Mensagem recebida em [{}]: {}", topic, payload);

                    try {
                        DataDTO dto = objectMapper.readValue(payload, DataDTO.class);
                        dataForwardingService.postDataLogger(dto);
                    } catch (Exception e) {
                        log.warn("Erro ao processar mensagem: {}", e.getMessage());
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            client.connect(options);
            client.subscribe(topic);
            log.info("Agent conectado com mTLS. Escutando: {}", topic);

        } catch (Exception e) {
            log.error("Erro fatal ao iniciar Agent MQTT: {}", e.getMessage(), e);
        }
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