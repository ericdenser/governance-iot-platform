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

    private String name;
    
    @PrePersist
    public void generateSensorId() {
        if (this.sensorId == null) {
            this.sensorId = UUID.randomUUID().toString();
        }
    }

}
