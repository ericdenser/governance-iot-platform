package com.eric.agentmqtt.service;

import com.eric.agentmqtt.model.AgentBroadcastRequest;
import com.eric.agentmqtt.model.DeviceCommandMessage;
import com.eric.agentmqtt.model.AgentBroadcastResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class AgentBroadcastService {

    private final MqttAgent mqttAgent;
    private final ObjectMapper objectMapper;

    public AgentBroadcastService(MqttAgent mqttAgent, ObjectMapper objectMapper) {
        this.mqttAgent = mqttAgent;
        this.objectMapper = objectMapper;
    }

    public AgentBroadcastResult broadcast(AgentBroadcastRequest request) {
        List<String> published = new ArrayList<>();
        List<String> failed    = new ArrayList<>();

        for (String targetDev : request.targetDevices()) {
            String topic    = "commands/" + targetDev;
            DeviceCommandMessage response = new DeviceCommandMessage(
                request.command(), request.payload(), targetDev
            );
            try {
                String json = objectMapper.writeValueAsString(response);
                mqttAgent.publish(topic, json, 1, false);
                published.add(targetDev);
            } catch (Exception e) {
                log.error("Falha ao publicar em [{}]: {}", topic, e.getMessage());
                failed.add(targetDev);
            }
        }

        log.info("Broadcast [{}] finalizado — ok: {}, falhas: {}",
                 request.command(), published.size(), failed.size());


        return new AgentBroadcastResult(
        request.command(),
        published,
        failed
        );
    }
}
