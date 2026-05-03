package com.eric.governanceApi.governanceApi.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.eric.governanceApi.governanceApi.enums.DeviceStatus;
import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.model.Device;
import com.eric.governanceApi.governanceApi.model.DeviceCertificate;
import com.eric.governanceApi.governanceApi.model.DeviceRegistrationRequest;
import com.eric.governanceApi.governanceApi.model.ProvisioningToken;
import com.eric.governanceApi.governanceApi.model.RegisterDeviceRequest;
import com.eric.governanceApi.governanceApi.repository.DeviceCertificateRepository;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
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

    public DeviceProvisioningService(DeviceRepository deviceRepository, ProvisioningTokenRepository tokenRepository, DeviceCertificateRepository deviceCertificateRepository, CryptoService cryptoService) {
        this.deviceRepository = deviceRepository;
        this.tokenRepository = tokenRepository;
        this.deviceCertificateRepository = deviceCertificateRepository;
        this.cryptoService = cryptoService; 
    }

    @Transactional
    public ProvisioningToken registerDevice(RegisterDeviceRequest request) {
        String deviceName = request.deviceName();

        if (deviceName == null || deviceName.trim().isEmpty()) {
            throw new IllegalArgumentException("The field 'deviceName' is missing.");
        }

        Device newDevice = new Device();
        newDevice.setName(deviceName);
        newDevice.setStatus(DeviceStatus.PENDING);
        deviceRepository.save(newDevice);

        ProvisioningToken provisioningToken = new ProvisioningToken(newDevice, 600); // 10 minutos de vida
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
            System.out.println("Device nao esta pending");
            throw new SecurityException("Device not in PENDING status.");
        }

        // Se já existe um dispositivo com este macAddress
        Optional<Device> existingDevice = deviceRepository.findByMacAddress(request.getMacAddress());
        if (existingDevice.isPresent()) {
            System.out.println("MAC ADDRESS ja registrado");
            throw new SecurityException("MAC Address already registered.");
        }

        // Queima o token
        token.setUsed(true);
        tokenRepository.save(token);

        SignedCertificateData certData;
        try {
             // Passa o CSR e o MAC para o CryptoService
             certData = cryptoService.signDeviceCSR(request.getPublicKey(), request.getMacAddress());
        } catch (Exception e) {
             throw new RuntimeException("Erro ao assinar certificado do dispositivo: " + e.getMessage(), e);
        }

        // Cria o registro do certificado no CMDB, extraindo as datas DIRETAMENTE do X509
        DeviceCertificate cert = new DeviceCertificate();
        cert.setCertificatePem(certData.pemString);
        cert.setDevice(device);
        cert.setIssuedAt(certData.certificateObj.getNotBefore().toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime());
        cert.setExpiresAt(certData.certificateObj.getNotAfter().toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime());

        deviceCertificateRepository.save(cert);

        // Atualiza Device
        device.setMacAddress(request.getMacAddress());
        device.setStatus(DeviceStatus.ACTIVE);
        device.setLastSeen(LocalDateTime.now());
        deviceRepository.save(device);

        // Retorna o PEM do certificado para o ESP32 guardar na memória (NVS)
        return certData.pemString;
    }
}