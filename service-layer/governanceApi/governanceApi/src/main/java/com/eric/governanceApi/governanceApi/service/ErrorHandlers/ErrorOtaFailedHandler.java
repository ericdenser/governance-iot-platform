package com.eric.governanceApi.governanceApi.service.ErrorHandlers;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eric.governanceApi.governanceApi.enums.DeviceError;
import com.eric.governanceApi.governanceApi.enums.status.CommandStatus;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.enums.status.ErrorStatus;
import com.eric.governanceApi.governanceApi.model.entity.CommandRecord;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.ErrorRecord;
import com.eric.governanceApi.governanceApi.model.request.DeviceErrorDTO;
import com.eric.governanceApi.governanceApi.repository.CommandRecordRepository;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.ErrorRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class ErrorOtaFailedHandler implements ErrorHandlerInterface {

    private final DeviceRepository deviceRepository;
    private final ErrorRecordRepository errorRecordRepository;
    private final CommandRecordRepository commandRecordRepository;

    @Override
    public DeviceError handles() { return DeviceError.OTA_FAIL; }

    @Override
    @Transactional
    public void process(DeviceErrorDTO errorDTO) {
        log.info("PROCESSANDO ERRO {} PARA DEVICE ID-> {}", errorDTO.errorCode(), errorDTO.deviceId());

        Instant ts = errorDTO.deviceTimestamp() != null ? errorDTO.deviceTimestamp() : Instant.now();

        if (errorDTO.resolved()) {
            log.info("ERRO {} PARA DEVICE ID-> {} FOI MARCADO COMO RESOLVED", errorDTO.errorCode(), errorDTO.deviceId());
            errorRecordRepository.findLatestByDeviceAndErrorAndStatus(
                errorDTO.deviceId(), DeviceError.OTA_FAIL, ErrorStatus.PENDING
                ).ifPresentOrElse(record -> {
                    record.setStatus(ErrorStatus.FIXED);
                    record.setFixedAt(ts);
                    errorRecordRepository.save(record);
                    log.info("ErrorRecord [{}] marcado como FIXED para device [{}]", record.getId(), errorDTO.deviceId());
                }, () ->
                    log.warn("Nenhum ErrorRecord PENDING de OTA_FAIL encontrado para device [{}]",
                    errorDTO.deviceId())
                );
            return;
        }

        ErrorRecord errorRecord = new ErrorRecord();

        errorRecord.setError(DeviceError.fromCode(errorDTO.errorCode()));
        errorRecord.setReportedAt(ts);
        errorRecord.setMessage(errorDTO.errorMsg());
        errorRecord.setDetails(errorDTO.extra());


        Optional<Device> deviceOptional = deviceRepository.findByDeviceId(errorDTO.deviceId());

        if (deviceOptional.isEmpty()) {
            log.warn("Device de ID {} não encontrado.", errorDTO.deviceId());
            errorRecord.setDevice(null);
            errorRecordRepository.save(errorRecord);
            return;
        }

        Device device = deviceOptional.get();
        device.setLastSeen(ts);
        errorRecord.setDevice(device);


        Optional<CommandRecord> pendingCommand = commandRecordRepository
                .findFirstByTargetDevice_DeviceIdAndStatus(device.getDeviceId(), CommandStatus.PENDING);

        if (!pendingCommand.isPresent()) {
            log.warn("Device de ID {} falhou ao executar comando, mas não há comandos PENDING no banco", device.getDeviceId());

            device.setStatus(DeviceStatus.ACTIVE);
            errorRecordRepository.save(errorRecord);
            return;
        }

        CommandRecord record = pendingCommand.get();
        record.setCompletedAt(ts);
        record.setStatus(CommandStatus.FAILED);
        device.setStatus(DeviceStatus.ACTIVE);

        log.warn("Device [{}] falhou ao executar OTA — payload: {}", errorDTO.deviceId(), record.getPayload());

        errorRecordRepository.save(errorRecord);
    }
}
