package com.eric.governanceApi.governanceApi.model.response;

import com.eric.governanceApi.governanceApi.model.entity.Sensor;

public record SensorResponseDTO(
    String sensorId,
    String name,
    String createdByActorId,
    String createdByUsername
) {

    public static SensorResponseDTO from(Sensor sensor) {
        return new SensorResponseDTO(
            sensor.getSensorId(),
            sensor.getName(),
            sensor.getCreatedByActorId(),
            sensor.getCreatedByUsername()
        );
    }
}
