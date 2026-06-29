package com.eric.governanceApi.governanceApi.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.eric.governanceApi.governanceApi.model.entity.DeviceGroup;

public interface DeviceGroupRepository extends JpaRepository<DeviceGroup, Long> {
    Optional<DeviceGroup> findByGroupId(String groupId);
    boolean existsByName(String name);
}
