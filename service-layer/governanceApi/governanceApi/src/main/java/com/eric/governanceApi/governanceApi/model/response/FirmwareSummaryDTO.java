package com.eric.governanceApi.governanceApi.model.response;

import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.model.entity.Firmware;

public record FirmwareSummaryDTO(
    String firmwareId,
    String version,
    FirmwareStatus status
) {
    public static FirmwareSummaryDTO from(Firmware fw) {
        if (fw == null) return null;
        return new FirmwareSummaryDTO(fw.getFirmwareId(), fw.getVersion(), fw.getStatus());
    }
}
