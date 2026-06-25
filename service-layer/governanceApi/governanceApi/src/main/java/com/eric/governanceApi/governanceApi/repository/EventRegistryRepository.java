package com.eric.governanceApi.governanceApi.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;

public interface EventRegistryRepository extends JpaRepository<EventRegistry, Long>{
    Page<EventRegistry> findByDevice_DeviceId(String deviceId, Pageable pageable);
    Page<EventRegistry> findAllByOrderByUploadedAtDesc(Pageable pageable);
}
