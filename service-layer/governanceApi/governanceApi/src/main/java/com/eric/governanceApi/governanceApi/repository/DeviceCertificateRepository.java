package com.eric.governanceApi.governanceApi.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.eric.governanceApi.governanceApi.model.entity.DeviceCertificate;

public interface DeviceCertificateRepository extends JpaRepository<DeviceCertificate, Long> {
}