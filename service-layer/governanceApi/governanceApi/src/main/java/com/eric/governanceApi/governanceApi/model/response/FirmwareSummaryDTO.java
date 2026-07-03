package com.eric.governanceApi.governanceApi.model.response;

import com.eric.governanceApi.governanceApi.model.entity.Firmware;

public record FirmwareSummaryDTO(
    String firmwareId,
    String firmwareName,
    boolean isProvisioning
) {
    public static FirmwareSummaryDTO from(Firmware fw) {
        if (fw == null) return null;
        return new FirmwareSummaryDTO(fw.getFirmwareId(), fw.getFirmwareName(), fw.isProvisioningFirmware());
    }
}
