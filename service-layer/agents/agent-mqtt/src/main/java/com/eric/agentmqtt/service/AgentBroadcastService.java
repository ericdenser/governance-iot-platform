package com.eric.agentmqtt.service;

import com.eric.agentmqtt.model.AgentBroadcastRequest;
import com.eric.agentmqtt.model.DeviceCommandMessage;
import com.eric.agentmqtt.model.AgentBroadcastResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class AgentBroadcastService {

    private final MqttAgent mqttAgent;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService waveScheduler = Executors.newSingleThreadScheduledExecutor();

    // Above threshold broadcast is divided in waves to not overload broker/MinIO
    @Value("${broadcast.wave-threshold:100}")
    private int waveThreshold;

    @Value("${broadcast.wave-size:50}")
    private int waveSize;

    @Value("${broadcast.wave-interval-ms:10000}")
    private long waveIntervalMs;

    public AgentBroadcastService(MqttAgent mqttAgent, ObjectMapper objectMapper) {
        this.mqttAgent = mqttAgent;
        this.objectMapper = objectMapper;
    }

    public AgentBroadcastResult broadcast(AgentBroadcastRequest request) {
        List<String> targets = request.targetDevices();

        if (targets.size() <= waveThreshold) {
            List<String> published = new ArrayList<>();
            List<String> failed    = new ArrayList<>();
            publishWave(request, targets, published, failed);

            log.info("Broadcast [{}] finalizado — ok: {}, falhas: {}",
                     request.command(), published.size(), failed.size());

            return new AgentBroadcastResult(request.command(), published, failed);
        }

        // Rollout em waves: First wave synchronous (feedback real pro govApi), demais agendadas.
        // Fail on async wave -> device stays as COMMAND_PENDING, timeout at govApi
        List<List<String>> waves = partition(targets, waveSize);
        List<String> published = new ArrayList<>();
        List<String> failed    = new ArrayList<>();

        publishWave(request, waves.get(0), published, failed);

        for (int i = 1; i < waves.size(); i++) {
            List<String> wave = waves.get(i);
            int waveNum = i + 1;
            int totalWaves = waves.size();
            waveScheduler.schedule(() -> {
                List<String> ok  = new ArrayList<>();
                List<String> nok = new ArrayList<>();
                publishWave(request, wave, ok, nok);
                log.info("Wave {}/{} [{}] — ok: {}, falhas: {}",
                         waveNum, totalWaves, request.command(), ok.size(), nok.size());
            }, (long) i * waveIntervalMs, TimeUnit.MILLISECONDS);
            published.addAll(wave);  // aceitos: publicação agendada
        }

        log.info("Broadcast [{}] fatiado em {} waves de até {} devices (intervalo {}ms) — wave 1: ok {}, falhas {}",
                 request.command(), waves.size(), waveSize, waveIntervalMs,
                 published.size() - (targets.size() - waves.get(0).size()), failed.size());

        return new AgentBroadcastResult(request.command(), published, failed);
    }

    private void publishWave(AgentBroadcastRequest request, List<String> wave,
                             List<String> published, List<String> failed) {
        for (String targetDev : wave) {
            String topic = "commands/" + targetDev;
            DeviceCommandMessage message = new DeviceCommandMessage(
                request.command(), request.payload(), targetDev
            );
            try {
                String json = objectMapper.writeValueAsString(message);
                mqttAgent.publish(topic, json, 1, false);
                published.add(targetDev);
            } catch (Exception e) {
                log.error("Falha ao publicar em [{}]: {}", topic, e.getMessage());
                failed.add(targetDev);
            }
        }
    }

    private static List<List<String>> partition(List<String> list, int size) {
        List<List<String>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return parts;
    }

    @PreDestroy
    void shutdown() {
        waveScheduler.shutdown();
    }
}
