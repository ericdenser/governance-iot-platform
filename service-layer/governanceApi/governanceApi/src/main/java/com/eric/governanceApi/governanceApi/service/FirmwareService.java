package com.eric.governanceApi.governanceApi.service;

import com.eric.governanceApi.governanceApi.config.AgentClient;
import com.eric.governanceApi.governanceApi.enums.DeviceCommands;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.exceptions.ConflictException;
import com.eric.governanceApi.governanceApi.exceptions.InfrastructureException;
import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.model.entity.CommandRecord;
import com.eric.governanceApi.governanceApi.model.entity.Firmware;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareSensorConfig;
import com.eric.governanceApi.governanceApi.model.request.FirmwareUploadMetadataDTO;
import com.eric.governanceApi.governanceApi.model.request.SensorConfigDTO;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.FirmwareRepository;
import com.eric.governanceApi.governanceApi.repository.SensorRepository;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@Slf4j
@Service
public class FirmwareService {

    private final FirmwareRepository firmwareRepository;
    private final DeviceRepository deviceRepository;
    private final AgentClient agentClient;
    private final SensorRepository sensorRepository;
    //private final ObjectMapper mapper = new ObjectMapper();

    @Value("${ota.firmware-storage-path}")
    private String storagePath;

    @Value("${ota.public-base-url}")
    private String publicBaseUrl;

    @Value("${ota.max-firmware-size-mb:4}")
    private int maxSizeMb;

    public FirmwareService(FirmwareRepository firmwareRepository,
                           DeviceRepository deviceRepository,
                           AgentClient agentClient, SensorRepository sensorRepository) {
        this.firmwareRepository = firmwareRepository;
        this.deviceRepository = deviceRepository;
        this.agentClient = agentClient;
        this.sensorRepository = sensorRepository;
    }


    //  UPLOAD FIRMWARE: valida, salva no disco, registra no banco
    @Transactional
    public Firmware upload(MultipartFile file, FirmwareUploadMetadataDTO metadataDTO) throws Exception {

        // Não podemos subir 2 firmwares de provisionamento
        if (metadataDTO.isProvisioning() && firmwareRepository.findByProvisioningFirmwareTrue().isPresent()) {

            throw new ConflictException("Provisioning firmware already exists");
        }

        validateBinary(file, metadataDTO.version());

        String sha256    = computeSha256(file);
        String filename = "firmware_v" + metadataDTO.version() + "_" + sha256.substring(0, 12) + ".bin";
        Path dest        = Paths.get(storagePath, filename);
        
        try {
            Files.createDirectories(dest.getParent());
            Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new InfrastructureException("Erro ao salvar firmware no disco: " + e.getMessage());
        }

        dest.toFile().setReadable(true, true);
        dest.toFile().setWritable(false);

        Firmware fw = new Firmware();
        fw.setVersion(metadataDTO.version());
        fw.setFilename(filename);
        fw.setOriginalFilename(file.getOriginalFilename());
        fw.setSha256(sha256);
        fw.setSizeBytes(file.getSize());
        fw.setDownloadUrl(publicBaseUrl + "/" + filename);
        fw.setReleaseNotes(metadataDTO.releaseNotes());
        fw.setProvisioningFirmware(metadataDTO.isProvisioning());
        fw.setStatus(FirmwareStatus.STAGED);


        // Valida sensores
        if (metadataDTO.sensors() != null && !metadataDTO.sensors().isEmpty()) {
            for (SensorConfigDTO sensorDto : metadataDTO.sensors()) {
                sensorRepository.findBySensorId(sensorDto.sensorId()).ifPresent(sensor -> {

                    FirmwareSensorConfig cfg = new FirmwareSensorConfig();
                    cfg.setFirmware(fw);
                    cfg.setPin(sensorDto.pin());
                    cfg.setSensor(sensor);

                    fw.getSensorConfigs().add(cfg);
                }
                );
            }
        }

        firmwareRepository.save(fw);

        log.info("Firmware v{} registrado — SHA256: {} | {} bytes | ID: {}",
                 metadataDTO.version(), sha256, file.getSize(), fw.getId());

        return fw;
    }

    //  DEPLOY: seleciona firmware existente + lista de devices
    @Transactional
    public Map<String, Object> deploy(Long firmwareId, List<String> targetDevices) throws Exception {

        Firmware fw = firmwareRepository.findById(firmwareId)
            .orElseThrow(() -> new ResourceNotFoundException("Firmware ID " + firmwareId + " não encontrado."));

        if (fw.getStatus() == FirmwareStatus.DEPRECATED) {
            throw new IllegalArgumentException(
                "Firmware v" + fw.getVersion() + " está DEPRECATED e não pode ser deployado.");
        }

        // Monta payload que vai pro Agent
        Map<String, Object> payload = Map.of(
        "version", fw.getVersion(),
        "url",     fw.getDownloadUrl()
        );

        // Filtra devices ativos
        List<String> activeDevs = new ArrayList<>();
        List<String> skipped    = new ArrayList<>();

        for (String devId : targetDevices) {
             deviceRepository.findByDeviceId(devId).ifPresentOrElse(
                
                device -> {

                   if (device.getStatus() == DeviceStatus.COMMAND_PENDING) {
                        skipped.add(devId);
                        log.warn("Device {} ignorado. Já existe um comando pendente em execução.", devId);
                        return; // Interrompe este fluxo e vai para o próximo device do loop
                    }

                    if (device.getStatus() != DeviceStatus.ACTIVE) {
                        skipped.add(devId);
                        log.info("Device de ID {} não está ACTIVE, skippando...", devId);
                        return;
                    }

                    if (device.getFirmware().getVersion() == fw.getVersion()) {
                        skipped.add(devId);
                        log.warn("Device {} ignorado. Já está na versão {}", devId, fw.getVersion());
                        return; 
                    }

                    CommandRecord record = new CommandRecord();
                    record.setCommandType(DeviceCommands.UPDATE);
                    record.setPayload(payload.toString());
                                        
                    
                    device.addCommandRecord(record);
                    device.setStatus(DeviceStatus.COMMAND_PENDING);
                    activeDevs.add(devId);

                    log.info("Device de ID {} válido, status alterado para COMMAND_PENDING", devId);

                    deviceRepository.save(device);
                }, () -> {
                    skipped.add(devId);
                    log.info("Device de ID {} não encontrado, skippando...", devId);
                });
            
        }

        if (activeDevs.isEmpty()) {
            return Map.of(
                "firmwareId", fw.getId(),
                "version", fw.getVersion(),
                "publishedTo", List.of(),
                "skipped", skipped,
                "message", "Nenhum device ativo para atualizar"
            );
        }

        Map<String, Object> agentResult = agentClient.broadcastCommands(DeviceCommands.UPDATE, payload, activeDevs);

        // Atualiza status do firmware (deploy_count é atualizado pelo DeviceUpdatedHandler
        // quando o device confirma que está rodando o firmware)
        fw.setStatus(FirmwareStatus.DEPLOYED);
        firmwareRepository.save(fw);

        Map<String, Object> result = new HashMap<>();
        result.put("firmwareId", fw.getId());
        result.put("version", fw.getVersion());
        result.put("downloadUrl", fw.getDownloadUrl());
        result.put("sha256", fw.getSha256());
        result.put("publishedTo", agentResult.getOrDefault("publishedTo", List.of()));
        result.put("failed", agentResult.getOrDefault("failed", List.of()));
        result.put("skipped", skipped);
        return result;
    }

    //  DEPRECATE, impede novos deploys desse firmware
    @Transactional
    public Firmware deprecate(Long firmwareId) {
        Firmware fw = firmwareRepository.findById(firmwareId)
            .orElseThrow(() -> new ResourceNotFoundException("Firmware ID " + firmwareId + " não encontrado."));

        fw.setStatus(FirmwareStatus.DEPRECATED);
        firmwareRepository.save(fw);

        log.info("Firmware v{} (ID {}) marcado como DEPRECATED", fw.getVersion(), fw.getId());
        return fw;
    }

    //  LIST: retorna todos os firmwares ordenados por versão
    // TODO retornar lista de FirmwareResponseDTO para nao expor o ID do banco de dados
    public List<Firmware> listAll() {
        return firmwareRepository.findAllByOrderByVersionDesc();
    }

    // TODO retornar lista de FirmwareResponseDTO para nao expor o ID do banco de dados
    public List<Firmware> listDeployable() {
        List<Firmware> result = new ArrayList<>();
        result.addAll(firmwareRepository.findByStatusOrderByVersionDesc(FirmwareStatus.STAGED));
        result.addAll(firmwareRepository.findByStatusOrderByVersionDesc(FirmwareStatus.DEPLOYED));
        return result;
    }

    // TODO retornar lista de FirmwareResponseDTO para nao expor o ID do banco de dados
    @Transactional
    public Firmware setProvisioningFirmware(Long newFirmwareId) {
        firmwareRepository.findByProvisioningFirmwareTrue().ifPresent(
            current -> {
                current.setProvisioningFirmware(false);
                firmwareRepository.save(current);
            }
        );

        Firmware fw = firmwareRepository.findById(newFirmwareId)
                    .orElseThrow(() -> 
                        new ResourceNotFoundException("Firmware ID" + newFirmwareId + "não encontrado."));

        if (fw.getStatus() == FirmwareStatus.DEPRECATED) {
            throw new IllegalArgumentException(
                "Firmware v" + fw.getVersion() + "está DEPRECATED e não pode ser provisioning.");
        }

        if (fw.getDeployCount() > 0) {
            throw new IllegalArgumentException(
                 "Firmware v" + fw.getVersion() + "está deployed em dispositivos, não pode ser provisioning.");

        }

        fw.setProvisioningFirmware(true);
        return firmwareRepository.save(fw);
    }


    //  Validações do arqv binário (METODO AUXILIAR)
    private void validateBinary(MultipartFile file, String version) throws IOException {

        if (file.isEmpty())
            throw new IllegalArgumentException("Arquivo vazio.");

        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".bin"))
            throw new IllegalArgumentException("Apenas .bin aceito. Recebido: " + name);

        long maxBytes = (long) maxSizeMb * 1024 * 1024;
        if (file.getSize() > maxBytes)
            throw new IllegalArgumentException(String.format(
                "Excede %dMB. Recebido: %d bytes.", maxSizeMb, file.getSize()));

        if (file.getSize() < 30_000)
            throw new IllegalArgumentException(String.format(
                "Muito pequeno (%d bytes). Mínimo: ~30KB.", file.getSize()));

        byte[] header = new byte[16];
        try (InputStream is = file.getInputStream()) {
            if (is.read(header) < 16)
                throw new IllegalArgumentException("Arquivo curto demais.");
        }
        if ((header[0] & 0xFF) != 0xE9)
            throw new IllegalArgumentException(String.format(
                "Magic byte 0x%02X inválido. Esperado: 0xE9.", header[0] & 0xFF));

        if (firmwareRepository.existsByVersion(version))
            throw new IllegalArgumentException("Versão " + version + " já existe no banco.");
    }

    private String computeSha256(MultipartFile file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = file.getInputStream()) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = is.read(buf)) != -1) digest.update(buf, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}