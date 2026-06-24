package com.eric.governanceApi.governanceApi.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.eric.governanceApi.governanceApi.model.entity.Sensor;

@Repository
public interface SensorRepository extends JpaRepository<Sensor, Long>{
    
    Optional<Sensor> findBySensorId(String sensorId);

    Optional<Sensor> findByName(String sensorName);


}
