package com.eric.governanceApi.governanceApi.service;

import java.io.FileOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509CRL;
import java.util.List;

import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.eric.governanceApi.governanceApi.audit.Auditable;
import com.eric.governanceApi.governanceApi.enums.AuditAction;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.exceptions.ConflictException;
import com.eric.governanceApi.governanceApi.exceptions.InfrastructureException;
import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.model.entity.Device;

import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class DeviceRevokeService {
    private final DeviceRepository deviceRepository;
    private final CryptoService cryptoService;
    private final RestClient restClient;

    @Value("${infra.api-key}")
    private String infraApiKey;

    @Value("${mqtt.crl-path}")
    private String crlPath;

    public DeviceRevokeService(DeviceRepository deviceRepository, CryptoService cryptoService, RestClient restClient) {
        this.deviceRepository = deviceRepository;
        this.cryptoService = cryptoService;
        this.restClient = restClient;
    }



    @Auditable(action = AuditAction.DEVICE_REVOKED, targetType = "DEVICE", targetIdArg = 0)
    @Transactional
    public String revokeDevice(String deviceId) throws Exception {

        log.info("Iniciando processo de revogação para o dispositivo ID: {}", deviceId);
        Device device = deviceRepository.findByDeviceId(deviceId)
            .orElseThrow(() -> new ResourceNotFoundException("Device " + deviceId + " not found"));

        
        if (device.getStatus() == DeviceStatus.REVOKED) {
            log.warn("Device {} already revoked.", deviceId);
            throw new ConflictException("Device " + deviceId + " ja revogado");
        }

        device.setStatus(DeviceStatus.REVOKED);

        try {
            List<String> revokedCertificates = deviceRepository.findAllRevokedCertificates();

            X509CRL finalCrl = cryptoService.generateCRL(revokedCertificates);

            StringWriter sw = new StringWriter();
            try (PemWriter pw = new PemWriter(sw)) {
                pw.writeObject(new PemObject("X509 CRL", finalCrl.getEncoded()));
            }

            // Salva o sw.toString() no arquivo, em vez dos bytes brutos
            try (FileOutputStream fos = new FileOutputStream(crlPath)) {
                fos.write(sw.toString().getBytes(StandardCharsets.UTF_8));
            }

            log.info("File CRL updated successfully.");

        } catch (Exception e) {
            log.error("Falha ao gerar arquivo CRL no disco.", e);
            throw new RuntimeException("Falha ao gravar arquivo CRL no disco");
        }
         

        try {
            restClient.post()
                .uri("http://localhost:8089/reload-crl")
                .header("X-API-Key", infraApiKey)
                .retrieve()
                .toBodilessEntity();
            log.info("Broker updated.");
        } catch (ResourceAccessException e) {
            log.error("Container infra-executor (Go) está offline ou inacessível. Causa raiz: {}", e.getMessage());
            throw new InfrastructureException("O serviço de infraestrutura do Broker está offline. A revogação foi cancelada para manter a consistência.");
            
        } catch (Exception e) {
            log.error("Falha inesperada ao tentar comunicar com GO:", e);
            throw new IllegalStateException("Falha na sincronização com o Broker MQTT."); 
        }

        return "Device " + deviceId + " revogado com sucesso.";
    }
}
