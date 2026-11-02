package com.eric.governanceApi.governanceApi.model.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Sensor extends AuthoredEntity {
    
    @Id 
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sensor_id", unique = true, nullable = false, updatable = false)
    private String sensorId;

    // Same string the firmware reports in activeSensors
    @Column(name = "code", unique = true, nullable = false, length = 24)
    private String code;

    private String name;
    
    @PrePersist
    public void generateSensorId() {
        if (this.sensorId == null) {
            this.sensorId = UUID.randomUUID().toString();
        }
    }

}
