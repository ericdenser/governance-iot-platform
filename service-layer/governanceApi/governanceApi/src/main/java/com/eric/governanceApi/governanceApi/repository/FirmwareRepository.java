package com.eric.governanceApi.governanceApi.repository;

import com.eric.governanceApi.governanceApi.enums.FirmwareStatus;
import com.eric.governanceApi.governanceApi.model.Firmware;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FirmwareRepository extends JpaRepository<Firmware, Long> {

    Optional<Firmware> findByVersion(int version);

    List<Firmware> findByStatusOrderByVersionDesc(FirmwareStatus status);

    List<Firmware> findAllByOrderByVersionDesc();

    boolean existsByVersion(int version);
}