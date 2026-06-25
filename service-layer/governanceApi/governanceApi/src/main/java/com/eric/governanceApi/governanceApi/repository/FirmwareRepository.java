package com.eric.governanceApi.governanceApi.repository;

import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.model.entity.Firmware;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FirmwareRepository extends JpaRepository<Firmware, Long> {

    Optional<Firmware> findByVersion(String version);

    // busca qual o firmware que é o provisioning
    Optional<Firmware> findByProvisioningFirmwareTrue();

    List<Firmware> findByStatusOrderByVersionDesc(FirmwareStatus status);

    List<Firmware> findAllByOrderByVersionDesc();

    boolean existsByVersion(String version);

    Optional<Firmware> findByFirmwareId(String firmwareId);
}