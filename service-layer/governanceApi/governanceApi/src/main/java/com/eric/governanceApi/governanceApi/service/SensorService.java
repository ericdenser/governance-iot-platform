package com.eric.governanceApi.governanceApi.service;

import org.springframework.stereotype.Service;

import com.eric.governanceApi.governanceApi.audit.Auditable;
import com.eric.governanceApi.governanceApi.enums.AuditAction;
import com.eric.governanceApi.governanceApi.enums.ErrorCode;
import com.eric.governanceApi.governanceApi.exceptions.ConflictException;
import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.model.entity.Sensor;
import com.eric.governanceApi.governanceApi.model.request.SensorDTO;
import com.eric.governanceApi.governanceApi.model.request.ValidationPatterns;
import com.eric.governanceApi.governanceApi.model.response.SensorResponseDTO;
import com.eric.governanceApi.governanceApi.repository.SensorRepository;

import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        sensorRepository.findByCode(sensorDTO.code()).ifPresent(sensor -> {
            throw new ConflictException(ErrorCode.SENSOR_CODE_DUPLICATE,
                "Sensor com código " + sensor.getCode() + " já existe.");
        });

        Sensor newSensor = new Sensor();
        newSensor.setCode(sensorDTO.code());
        String name = sensorDTO.name();
        newSensor.setName(name == null || name.isBlank() ? sensorDTO.code() : name.trim());
        sensorRepository.save(newSensor);

        return SensorResponseDTO.from(newSensor);
    }

    @Auditable(action = AuditAction.SENSOR_RENAMED, targetType = "SENSOR", targetIdArg = 0)
    @Transactional
    public SensorResponseDTO rename(String sensorId, String name) {
        Sensor sensor = sensorRepository.findBySensorId(sensorId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SENSOR_NOT_FOUND,
                    "Sensor " + sensorId + " não encontrado."));
        sensor.setName(name.trim());
        return SensorResponseDTO.from(sensor);
    }

    @Transactional(readOnly = true)
    public Map<String, Sensor> findByCodes(Collection<String> codes) {
        Map<String, Sensor> byCode = new HashMap<>();
        for (Sensor s : sensorRepository.findByCodeIn(codes)) byCode.put(s.getCode(), s);
        return byCode;
    }

    // Codes reported by devices that are not in the catalog yet are added with name = code
    @Transactional
    public Map<String, Sensor> ensureRegistered(Collection<String> codes) {
        Map<String, Sensor> byCode = findByCodes(codes);

        for (String code : codes) {
            if (byCode.containsKey(code)) continue;
            if (!code.matches(ValidationPatterns.SENSOR_CODE_REGEX)) {
                log.warn("Código de sensor inválido ignorado: [{}]", code);
                continue;
            }
            Sensor s = new Sensor();
            s.setCode(code);
            s.setName(code);
            byCode.put(code, sensorRepository.save(s));
            log.info("Sensor [{}] adicionado ao catálogo automaticamente.", code);
        }
        return byCode;
    }
}
