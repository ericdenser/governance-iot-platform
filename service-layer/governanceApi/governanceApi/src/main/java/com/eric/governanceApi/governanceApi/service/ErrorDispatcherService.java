package com.eric.governanceApi.governanceApi.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.eric.governanceApi.governanceApi.enums.DeviceError;
import com.eric.governanceApi.governanceApi.enums.status.ErrorStatus;
import com.eric.governanceApi.governanceApi.model.dto.DeviceErrorDTO;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.ErrorRecord;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.ErrorRecordRepository;
import com.eric.governanceApi.governanceApi.service.ErrorHandlers.ErrorHandlerInterface;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ErrorDispatcherService {
    private final Map<DeviceError, ErrorHandlerInterface> handlers;
    private final DeviceRepository deviceRepository;
    private final ErrorRecordRepository errorRecordRepository;

    public ErrorDispatcherService(List<ErrorHandlerInterface> allHandlers, DeviceRepository deviceRepository, ErrorRecordRepository errorRecordRepository) {
        this.handlers = allHandlers.stream()
        .collect(Collectors.toMap(ErrorHandlerInterface::handles, h -> h));   

        this.deviceRepository = deviceRepository;
        this.errorRecordRepository = errorRecordRepository;
    }
    

    public void dispatch(DeviceErrorDTO errorDTO) {
        DeviceError error;

        if (errorDTO.errorCode() == null) {
            log.warn("Error code is null");
            return;
        }
        error = DeviceError.fromCode(errorDTO.errorCode());

        // Mapeou, vemos se existe uma logica de negocio implementada para este evento
        ErrorHandlerInterface handler = handlers.get(error);


        // Se handler for null, apenas registramos o erro
        if (handler == null) {
            log.warn("No handler registered for: {}", error);

            Device device = deviceRepository.findByDeviceId(errorDTO.deviceId()).orElse(null);
            if (device == null) {
                log.warn("Device {} not found — skipping ErrorRecord for error {}", errorDTO.deviceId(), error);
                return;
            }

            log.warn("Saving error record...");

            ErrorRecord errorRecord = new ErrorRecord();
            errorRecord.setError(DeviceError.fromCode(errorDTO.errorCode()));
            errorRecord.setReportedAt(LocalDateTime.now());
            errorRecord.setMessage(errorDTO.errorMsg());
            errorRecord.setDetails(errorDTO.extra());
            errorRecord.setDevice(device);

            device.setLastSeen(LocalDateTime.now());
            deviceRepository.save(device);

            if (errorDTO.resolved()) {
                errorRecord.setFixedAt(LocalDateTime.now());
                errorRecord.setStatus(ErrorStatus.FIXED);
            }

            errorRecordRepository.save(errorRecord);
            return;
        }

        handler.process(errorDTO);
    }


}
