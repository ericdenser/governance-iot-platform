package com.eric.governanceApi.governanceApi.service;

import com.eric.governanceApi.governanceApi.config.AgentClient;
import com.eric.governanceApi.governanceApi.enums.DeviceCommands;
import com.eric.governanceApi.governanceApi.model.request.CommandRequest;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
public class CommandsService {

    private final DeviceRepository deviceRepository;
    private final AgentClient agentClient;
    private final FirmwareService firmwareService;
    //private final ObjectMapper mapper = new ObjectMapper();

    public CommandsService(DeviceRepository deviceRepository, AgentClient agentClient, FirmwareService firmwareService) {
        this.deviceRepository = deviceRepository;
        this.agentClient = agentClient;
        this.firmwareService = firmwareService;
    }

    // Interpreta qual foi o comando
    public Map<String, Object> execute(CommandRequest request) throws Exception {
        return switch (request.command()) {
            case UPDATE    -> handleUpdate(request);
            case REBOOT    -> handleSimpleCommand(request);
            case DEEP_SLEEP -> handleSimpleCommand(request);
        };
    }

    // Se foi OTA
    private Map<String, Object> handleUpdate(CommandRequest request) throws Exception {

        if (request.params() == null || !request.params().containsKey("firmwareId")) {
            throw new IllegalArgumentException("UPDATE exige 'firmwareId' em params.");
        }

        Long firmwareId = ((Number) request.params().get("firmwareId")).longValue();

        // Delega deploy ao FirmwareService dedicado exclusivamente para OTA
        return firmwareService.deploy(firmwareId, request.targetDevices());

    }

    //  Comandos simples — reboot, deepsleep, etc
    private Map<String, Object> handleSimpleCommand(CommandRequest request) {

        log.info("Verificando situação dos devices destinatários.");

        List<String> activeDevs = new ArrayList<>();
        List<String> skipped    = new ArrayList<>();

        // PROCURA TODOS QUE ESTAO ATIVOS
        for (String devId : request.targetDevices()) {
            boolean isActive = deviceRepository.findByDeviceId(devId)
                    .map(d -> d.getStatus().name().equals("ACTIVE"))
                    .orElse(false);

            if (isActive) {
                activeDevs.add(devId);
                log.info("Device de ID {} valido, adicionando a lista de destinatarios...", devId);
            } else {
                skipped.add(devId);
                log.info("Device de Id {} nao valido, skippando...", devId);
            }
        }

        // NENHUM DEVICE ATIVO
        if (activeDevs.isEmpty()) {

            log.info ("Nenhum Device ativo");
            return Map.of(
                "command", request.command().name(),
                "publishedTo", List.of(),
                "skipped", skipped,
                "message", "Nenhum device ativo"
            );
        }


        // Constroi payload
        Map<String, Object> payload = buildPayload(request.command(), request.params());

        // Envia pro Agent
        Map<String, Object> agentResult = agentClient.broadcastCommands(request.command(), payload, activeDevs);

        // Resultado do Agent
        Map<String, Object> result = new HashMap<>();
        result.put("command", request.command().name());
        result.put("publishedTo", agentResult.getOrDefault("publishedTo", List.of()));
        result.put("failed", agentResult.getOrDefault("failed", List.of()));
        result.put("skipped", skipped);
        return result;
    }

    // Metodo para construir payload generico contendo comando e os parametros
    private Map<String, Object> buildPayload(DeviceCommands command, Map<String, Object> params) {
        Map<String, Object> payload = new HashMap<>();

        switch (command) {
            case REBOOT -> {

                int delayMs = 3000;
                if (params != null && params.containsKey("delay_ms")) {
                    delayMs = ((Number) params.get("delay_ms")).intValue();
                }
                payload.put("delay_ms", delayMs);

            }
            case DEEP_SLEEP -> {

                if (params == null || !params.containsKey("duration_s")) {
                    throw new IllegalArgumentException("DEEP_SLEEP exige 'duration_s' em params.");
                }

                int durationS = ((Number) params.get("duration_s")).intValue();

                // 1 minuto ou 3 dias
                if (durationS < 10 || durationS > 259200) {
                    throw new IllegalArgumentException("duration_s deve estar entre 60 e 259200.");
                }

                payload.put("duration_s", durationS);
            }
            default -> {}
        }

        
        return payload;
    }
}