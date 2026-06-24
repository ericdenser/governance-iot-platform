package com.eric.governanceApi.governanceApi.service;

import org.springframework.stereotype.Service;

import com.eric.governanceApi.governanceApi.exceptions.ConflictException;
import com.eric.governanceApi.governanceApi.model.entity.Sensor;
import com.eric.governanceApi.governanceApi.model.request.SensorDTO;
import com.eric.governanceApi.governanceApi.model.response.SensorResponseDTO;
import com.eric.governanceApi.governanceApi.repository.SensorRepository;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class SensorService {

    private final SensorRepository sensorRepository;

    public SensorService(SensorRepository sensorRepository) {
        this.sensorRepository = sensorRepository;
    }


    @Transactional
    public SensorResponseDTO registerSensor(SensorDTO sensorDTO) {

        sensorRepository.findByName(sensorDTO.name()).ifPresent(sensor -> {
            throw new ConflictException("Sensor " + sensor.getName() +  " já existe");
        });

        Sensor newSensor = new Sensor();
        newSensor.setName(sensorDTO.name());
        sensorRepository.save(newSensor);

        SensorResponseDTO response = new SensorResponseDTO(newSensor.getSensorId(), newSensor.getName());
    
        return response;
    }


}
