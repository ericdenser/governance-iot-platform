package com.eric.agent.service;

import com.eric.agent.model.AgentBroadcastRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class AgentBroadcastService {

    private final MqttAgent mqttAgent;

    public AgentBroadcastService(MqttAgent mqttAgent) {
        this.mqttAgent = mqttAgent;
    }

    /*
     Publica o payload no tópico commands/<MAC>/<subtopic> para cada MAC.
     Interpreta o tipo de comando OTA, reboot, etc.
    */
    public Map<String, Object> broadcast(AgentBroadcastRequest request) {

        List<String> published = new ArrayList<>();
        List<String> failed    = new ArrayList<>();

        for (String mac : request.targetMacs()) {
            String topic = "commands/" + mac + "/" + request.subtopic();
            try {
                // QoS 1 = entrega garantida; retained = true para devices offline
                boolean retained = "ota".equals(request.subtopic()); // se for OTA retém
                mqttAgent.publish(topic, request.payload(), 1, retained);
                published.add(mac);
            } catch (Exception e) {
                log.error("Falha ao publicar em [{}]: {}", topic, e.getMessage());
                failed.add(mac);
            }
        }

        log.info("Broadcast [{}] finalizado — ok: {}, falhas: {}",
                 request.subtopic(), published.size(), failed.size());

        return Map.of(
            "subtopic",    request.subtopic(),
            "publishedTo", published,
            "failed",      failed
        );
    }
}