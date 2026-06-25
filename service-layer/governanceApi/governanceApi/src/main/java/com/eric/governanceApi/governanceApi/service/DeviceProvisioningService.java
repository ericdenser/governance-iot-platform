package com.eric.governanceApi.governanceApi.service;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.DeviceCertificate;
import com.eric.governanceApi.governanceApi.model.entity.ProvisioningToken;
import com.eric.governanceApi.governanceApi.model.request.DeviceRegistrationRequest;
import com.eric.governanceApi.governanceApi.model.request.RegisterDeviceRequest;
import com.eric.governanceApi.governanceApi.repository.DeviceCertificateRepository;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.FirmwareRepository;
import com.eric.governanceApi.governanceApi.repository.ProvisioningTokenRepository;
import com.eric.governanceApi.governanceApi.service.CryptoService.SignedCertificateData;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DeviceProvisioningService {

    private final DeviceRepository deviceRepository;
    private final ProvisioningTokenRepository tokenRepository;
    private final DeviceCertificateRepository deviceCertificateRepository;
    private final CryptoService cryptoService;
    private final FirmwareRepository firmwareRepository;

    public DeviceProvisioningService(DeviceRepository deviceRepository, ProvisioningTokenRepository tokenRepository, DeviceCertificateRepository deviceCertificateRepository, CryptoService cryptoService, FirmwareRepository firmwareRepository) {
        this.deviceRepository = deviceRepository;
        this.tokenRepository = tokenRepository;
        this.deviceCertificateRepository = deviceCertificateRepository;
        this.cryptoService = cryptoService; 
        this.firmwareRepository = firmwareRepository;
    }

    @Transactional
    public ProvisioningToken registerDevice(RegisterDeviceRequest request) {
        return registerDevice(request, 600);
    }

    @Transactional
    public ProvisioningToken registerDevice(RegisterDeviceRequest request, int ttlSeconds) {
        String deviceName = request.deviceName();

        if (deviceName == null || deviceName.trim().isEmpty()) {
            throw new IllegalArgumentException("The field 'deviceName' is missing.");
        }

        Device newDevice = new Device();
        newDevice.setName(deviceName);
        newDevice.setStatus(DeviceStatus.PENDING);
        deviceRepository.save(newDevice);

        ProvisioningToken provisioningToken = new ProvisioningToken(newDevice, ttlSeconds);
        tokenRepository.save(provisioningToken);
        return provisioningToken;
    }

    @Transactional
    public String processDeviceRegistration(DeviceRegistrationRequest request) {
        log.info("Procurando token: {}", request.getProvisioningToken());
        // Se o token não foi emitido
        ProvisioningToken token = tokenRepository.findByToken(request.getProvisioningToken())
            .orElseThrow(() -> new ResourceNotFoundException("Token not found."));
        
        token.validate(); 

        // Se o status não é Pendente
        Device device = token.getDevice();
        if (device.getStatus() != DeviceStatus.PENDING) {
            log.info("Device nao esta pending");
            throw new SecurityException("Device not in PENDING status.");
        }

        // Valida que o device_id do request é o do device dono do token
        if (!device.getDeviceId().equals(request.getDeviceId())) {
            throw new SecurityException("Device ID does not match the provisioning token");
        }

        // Queima o token
        token.setUsed(true);

        SignedCertificateData certData;
        try {
             // Passa o CSR e o MAC para o CryptoService
             certData = cryptoService.signDeviceCSR(request.getPublicKey(), request.getDeviceId());
        } catch (Exception e) {
             throw new RuntimeException("Erro ao assinar certificado do dispositivo: " + e.getMessage(), e);
        }

        // Cria o registro do certificado no CMDB, extraindo as datas DIRETAMENTE do X509
        DeviceCertificate cert = new DeviceCertificate();
        cert.setCertificatePem(certData.pemString);
        cert.setDevice(device);
        cert.setIssuedAt(certData.certificateObj.getNotBefore().toInstant());
        cert.setExpiresAt(certData.certificateObj.getNotAfter().toInstant());

        deviceCertificateRepository.save(cert);
        
        // Atualiza Device
        device.setDeviceId(request.getDeviceId());
        device.setMacAddress(request.getMacAddress());
        device.setStatus(DeviceStatus.PROVISIONING);
        device.setLastSeen(Instant.now());
        device.setFirmware(firmwareRepository.findByProvisioningFirmwareTrue()
                .orElseThrow(() -> new ResourceNotFoundException("Nenhum firmware de provisioning registrado.")));

        // Retorna o PEM do certificado para o ESP32 guardar na memória (NVS)
        return certData.pemString;
    }
}