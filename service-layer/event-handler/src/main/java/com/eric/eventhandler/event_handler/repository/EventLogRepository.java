package com.eric.eventhandler.event_handler.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.eric.eventhandler.event_handler.enums.EventType;
import com.eric.eventhandler.event_handler.model.entity.EventLog;

public interface EventLogRepository extends JpaRepository<EventLog, Long> {
    
    List<EventLog> findByDeviceIdOrderByTimestampDesc(String device_id);
    
    List<EventLog> findByEventTypeOrderByTimestampDesc(EventType eventType);
 
    List<EventLog> findTop50ByOrderByTimestampDesc();

    boolean existsBySourceMessageId(String sourceMessageId);

}
