package com.eric.governanceApi.governanceApi.service;

import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.response.CommandRecordResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.DeviceCertificateResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.DeviceDetailDTO;
import com.eric.governanceApi.governanceApi.model.response.DeviceSummaryDTO;
import com.eric.governanceApi.governanceApi.model.response.ErrorRecordResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.EventRegistryResponseDTO;
import com.eric.governanceApi.governanceApi.repository.CommandRecordRepository;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.ErrorRecordRepository;
import com.eric.governanceApi.governanceApi.repository.EventRegistryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final CommandRecordRepository commandRecordRepository;
    private final ErrorRecordRepository errorRecordRepository;
    private final EventRegistryRepository eventRegistryRepository;

    public DeviceService(DeviceRepository deviceRepository,
                         CommandRecordRepository commandRecordRepository,
                         ErrorRecordRepository errorRecordRepository,
                         EventRegistryRepository eventRegistryRepository) {
        this.deviceRepository = deviceRepository;
        this.commandRecordRepository = commandRecordRepository;
        this.errorRecordRepository = errorRecordRepository;
        this.eventRegistryRepository = eventRegistryRepository;
    }

    @Transactional(readOnly = true)
    public List<DeviceSummaryDTO> listAll() {
        return deviceRepository.findAll().stream()
                .map(DeviceSummaryDTO::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DeviceDetailDTO getDevice(String deviceId) {
        Device device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device " + deviceId + " não encontrado."));
        return DeviceDetailDTO.from(device);
    }

    @Transactional(readOnly = true)
    public Page<CommandRecordResponseDTO> getCommands(String deviceId, Pageable pageable) {
        deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device " + deviceId + " não encontrado."));
        return commandRecordRepository.findByTargetDevice_DeviceId(deviceId, pageable)
                .map(CommandRecordResponseDTO::from);
    }

    @Transactional(readOnly = true)
    public Page<ErrorRecordResponseDTO> getErrors(String deviceId, Pageable pageable) {
        deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device " + deviceId + " não encontrado."));
        return errorRecordRepository.findByDevice_DeviceId(deviceId, pageable)
                .map(ErrorRecordResponseDTO::from);
    }

    @Transactional(readOnly = true)
    public Page<EventRegistryResponseDTO> getEvents(String deviceId, Pageable pageable) {
        deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device " + deviceId + " não encontrado."));
        return eventRegistryRepository.findByDevice_DeviceId(deviceId, pageable)
                .map(EventRegistryResponseDTO::from);
    }

    // todo (apenas adm pode ver todos os eventos de todos devices)
    @Transactional(readOnly = true)
    public Page<EventRegistryResponseDTO> listAllEvents(Pageable pageable) {
        return eventRegistryRepository.findAllByOrderByUploadedAtDesc(pageable)
                .map(EventRegistryResponseDTO::from);
    }

    @Transactional(readOnly = true)
    public DeviceCertificateResponseDTO getCertificate(String deviceId) {
        Device device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device " + deviceId + " não encontrado."));
        if (device.getCertificate() == null) {
            throw new ResourceNotFoundException("Device " + deviceId + " não possui certificado.");
        }
        return DeviceCertificateResponseDTO.from(device.getCertificate());
    }
}
