package com.eric.agent.service;

import com.eric.agent.model.AgentBroadcastRequest;
import com.eric.agent.model.AgentBroadcastResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

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
     Publica o payload no tópico commands/<MAC>/ para cada MAC.
     Interpreta o tipo de comando OTA, reboot, etc.
    */
    public Map<String, Object> broadcast(AgentBroadcastRequest request) {

        List<String> published = new ArrayList<>();
        List<String> failed    = new ArrayList<>();

        for (String targetDev : request.targetDevices()) {
            String topic = "commands/" + targetDev;
            AgentBroadcastResponse response = new AgentBroadcastResponse(request.command(), request.payload(), targetDev);
            try {

                ObjectMapper mapper = new ObjectMapper();
                String json = mapper.writeValueAsString(response);

                // QoS 1 = entrega garantida; retained = true para devices offline
                //boolean retained = "UPDATE".equals(request.command()); // se for OTA retém
                mqttAgent.publish(topic, json, 1, false);
                published.add(targetDev);
            } catch (Exception e) {
                log.error("Falha ao publicar em [{}]: {}", topic, e.getMessage());
                failed.add(targetDev);
            }
        }

        log.info("Broadcast [{}] finalizado — ok: {}, falhas: {}",
                 request.command(), published.size(), failed.size());

        return Map.of(
            "command",    request.command(),
            "publishedTo", published,
            "failed",      failed
        );
    }
}