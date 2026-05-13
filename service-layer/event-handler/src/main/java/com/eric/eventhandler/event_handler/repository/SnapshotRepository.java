package com.eric.eventhandler.event_handler.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.eric.eventhandler.event_handler.model.DeviceSnapshot;

public interface SnapshotRepository extends JpaRepository<DeviceSnapshot, String>{
    
}
