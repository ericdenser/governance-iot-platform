package com.eric.governanceApi.governanceApi.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.eric.governanceApi.governanceApi.model.DeviceCertificate;

public interface DeviceCertificateRepository extends JpaRepository<DeviceCertificate, Long> {
}