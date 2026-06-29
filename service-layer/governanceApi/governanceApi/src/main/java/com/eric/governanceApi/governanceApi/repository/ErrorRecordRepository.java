package com.eric.governanceApi.governanceApi.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.eric.governanceApi.governanceApi.enums.DeviceError;
import com.eric.governanceApi.governanceApi.enums.status.ErrorStatus;
import com.eric.governanceApi.governanceApi.model.entity.ErrorRecord;

public interface ErrorRecordRepository extends JpaRepository<ErrorRecord, Long> {

    @Query("SELECT e FROM ErrorRecord e WHERE e.device.deviceId = :deviceId AND e.error = :error AND e.status = :status ORDER BY e.reportedAt DESC LIMIT 1")
    Optional<ErrorRecord> findLatestByDeviceAndErrorAndStatus(
        @Param("deviceId") String deviceId,
        @Param("error") DeviceError error,
        @Param("status") ErrorStatus status
    );

    Optional<ErrorRecord> findFirstByDevice_DeviceIdAndErrorOrderByReportedAtDesc(
        String deviceId,
        DeviceError error
    );

    Page<ErrorRecord> findByDevice_DeviceId(String deviceId, Pageable pageable);
    Page<ErrorRecord> findAllByOrderByReportedAtDesc(Pageable pageable);
}
