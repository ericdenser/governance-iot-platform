package com.eric.governanceApi.governanceApi.service;

import org.springframework.stereotype.Service;

import com.eric.governanceApi.governanceApi.audit.Auditable;
import com.eric.governanceApi.governanceApi.enums.AuditAction;
import com.eric.governanceApi.governanceApi.exceptions.ConflictException;
import com.eric.governanceApi.governanceApi.model.entity.Sensor;
import com.eric.governanceApi.governanceApi.model.request.SensorDTO;
import com.eric.governanceApi.governanceApi.model.response.SensorResponseDTO;
import com.eric.governanceApi.governanceApi.repository.SensorRepository;

import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Service
@Slf4j
public class SensorService {

    private final SensorRepository sensorRepository;

    public SensorService(SensorRepository sensorRepository) {
        this.sensorRepository = sensorRepository;
    }


    @Transactional(readOnly = true)
    public List<SensorResponseDTO> listAll() {
        return sensorRepository.findAll().stream()
                .map(SensorResponseDTO::from)
                .toList();
    }

    @Auditable(action = AuditAction.SENSOR_REGISTERED, targetType = "SENSOR")
    @Transactional
    public SensorResponseDTO registerSensor(SensorDTO sensorDTO) {

        sensorRepository.findByName(sensorDTO.name()).ifPresent(sensor -> {
            throw new ConflictException("Sensor " + sensor.getName() + " já existe");
        });

        Sensor newSensor = new Sensor();
        newSensor.setName(sensorDTO.name());
        sensorRepository.save(newSensor);

        return SensorResponseDTO.from(newSensor);
    }


}
