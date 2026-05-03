package com.eric.governanceApi.governanceApi.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.eric.governanceApi.governanceApi.model.Device;

public interface DeviceRepository extends JpaRepository<Device, Long>{
    Optional<Device> findByMacAddress(String macAddress);

    @Query("SELECT d.certificate.certificatePem FROM Device d WHERE d.status = com.eric.governanceApi.governanceApi.enums.DeviceStatus.REVOKED")
    List<String> findAllRevokedCertificates();
}
