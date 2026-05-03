package com.eric.governanceApi.governanceApi.service;

import com.eric.governanceApi.governanceApi.config.AgentClient;
import com.eric.governanceApi.governanceApi.enums.FirmwareStatus;
import com.eric.governanceApi.governanceApi.exceptions.InfrastructureException;
import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.model.Firmware;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.FirmwareRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${ota.firmware-storage-path}")
    private String storagePath;

    @Value("${ota.public-base-url}")
    private String publicBaseUrl;

    @Value("${ota.max-firmware-size-mb:4}")
    private int maxSizeMb;

    public FirmwareService(FirmwareRepository firmwareRepository,
                           DeviceRepository deviceRepository,
                           AgentClient agentClient) {
        this.firmwareRepository = firmwareRepository;
        this.deviceRepository = deviceRepository;
        this.agentClient = agentClient;
    }


    //  UPLOAD: valida, salva no disco, registra no banco
    @Transactional
    public Firmware upload(MultipartFile file, int version, String releaseNotes) throws Exception {

        validateBinary(file, version);

        String sha256    = computeSha256(file);
        String filename  = String.format("firmware_v%d_%s.bin", version, sha256.substring(0, 12));
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
        fw.setVersion(version);
        fw.setFilename(filename);
        fw.setOriginalFilename(file.getOriginalFilename());
        fw.setSha256(sha256);
        fw.setSizeBytes(file.getSize());
        fw.setDownloadUrl(publicBaseUrl + "/" + filename);
        fw.setReleaseNotes(releaseNotes);
        fw.setStatus(FirmwareStatus.STAGED);

        firmwareRepository.save(fw);

        log.info("Firmware v{} registrado — SHA256: {} | {} bytes | ID: {}",
                 version, sha256, file.getSize(), fw.getId());

        return fw;
    }

    //  DEPLOY: seleciona firmware existente + lista de devices
    @Transactional
    public Map<String, Object> deploy(Long firmwareId, List<String> targetMacs) throws Exception {

        Firmware fw = firmwareRepository.findById(firmwareId)
            .orElseThrow(() -> new ResourceNotFoundException("Firmware ID " + firmwareId + " não encontrado."));

        if (fw.getStatus() == FirmwareStatus.DEPRECATED) {
            throw new IllegalArgumentException(
                "Firmware v" + fw.getVersion() + " está DEPRECATED e não pode ser deployado.");
        }

        // Filtra devices ativos
        List<String> activeMacs = new ArrayList<>();
        List<String> skipped    = new ArrayList<>();

        for (String mac : targetMacs) {
            boolean isActive = deviceRepository.findByMacAddress(mac)
                    .map(d -> d.getStatus().name().equals("ACTIVE"))
                    .orElse(false);

            if (isActive) {
                activeMacs.add(mac);
            } else {
                skipped.add(mac);
            }
        }

        if (activeMacs.isEmpty()) {
            return Map.of(
                "firmwareId", fw.getId(),
                "version", fw.getVersion(),
                "publishedTo", List.of(),
                "skipped", skipped,
                "message", "Nenhum device ativo para atualizar"
            );
        }

        // Monta payload e envia ao Agent
        String payload = mapper.writeValueAsString(Map.of(
            "version", fw.getVersion(),
            "url",     fw.getDownloadUrl()
        ));

        Map<String, Object> agentResult = agentClient.broadcast("ota", payload, activeMacs);

        // Atualiza status do firmware
        fw.setStatus(FirmwareStatus.DEPLOYED);
        fw.setDeployCount(fw.getDeployCount() + 1);
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
    public List<Firmware> listAll() {
        return firmwareRepository.findAllByOrderByVersionDesc();
    }

    public List<Firmware> listDeployable() {
        List<Firmware> result = new ArrayList<>();
        result.addAll(firmwareRepository.findByStatusOrderByVersionDesc(FirmwareStatus.STAGED));
        result.addAll(firmwareRepository.findByStatusOrderByVersionDesc(FirmwareStatus.DEPLOYED));
        return result;
    }


    //  Validações do arqv binário (METODO AUXILIAR)
    private void validateBinary(MultipartFile file, int version) throws IOException {

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