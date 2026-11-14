package com.eric.governanceApi.governanceApi.firmware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;
import com.eric.governanceApi.governanceApi.model.entity.Firmware;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareVersion;
import com.eric.governanceApi.governanceApi.model.request.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.model.request.DeviceInfoDTO;
import com.eric.governanceApi.governanceApi.repository.CommandRecordRepository;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.EventRegistryRepository;
import com.eric.governanceApi.governanceApi.service.EventHandlers.DeviceRollbackHandler;

class DeviceRollbackHandlerTest {

    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final EventRegistryRepository events = mock(EventRegistryRepository.class);
    private final DeviceRollbackHandler handler =
        new DeviceRollbackHandler(devices, events, mock(CommandRecordRepository.class));

    private static FirmwareVersion version(Firmware fw, String v, String sha, int deploys) {
        FirmwareVersion fv = new FirmwareVersion();
        fv.setFirmware(fw);
        fv.setVersion(v);
        fv.setSha256(sha);
        fv.setDeployCount(deploys);
        fv.setStatus(FirmwareStatus.DEPLOYED);
        return fv;
    }

    private EventRegistry runLateRollback(String reason, FirmwareVersion current, FirmwareVersion previous) {
        Device device = new Device();
        device.setDeviceId("dev-1");
        device.setFirmwareVersion(current);
        device.setPreviousFirmwareVersion(previous);
        when(devices.findByDeviceId("dev-1")).thenReturn(Optional.of(device));

        Map<String, Object> params = Map.of("invalid_ver", current.getVersion(),
                                            "invalid_sha", current.getSha256(), "reason", reason);
        handler.process(new DeviceEventWebhookDTO("DEVICE_FIRMWARE_ROLLBACK", "dev-1", "OPERATIONAL",
            "FIRMWARE_ROLLBACK", new DeviceInfoDTO(null, previous.getVersion(), null, null, params, null),
            Instant.now(), null));

        ArgumentCaptor<EventRegistry> saved = ArgumentCaptor.forClass(EventRegistry.class);
        verify(events).save(saved.capture());
        assertEquals(previous, device.getFirmwareVersion());
        return saved.getValue();
    }

    @Test
    void lateRollbackShowsCrashTypeAndDeprecates() {
        Firmware fw = new Firmware();
        fw.setFirmwareName("op");
        FirmwareVersion current  = version(fw, "1.2", "aa", 1);
        FirmwareVersion previous = version(fw, "1.1", "bb", 0);

        EventRegistry ev = runLateRollback("crashCount:PANIC", current, previous);

        assertEquals("Rollback tardio: v1.2 reiniciou por crash várias vezes seguidas (PANIC). Device revertido para v1.1."
            + " Versão v1.2 descontinuada (pode ser reativada na tela do firmware).", ev.getResultMessage());
        assertEquals(FirmwareStatus.DEPRECATED, current.getStatus());
    }

    @Test
    void oldFirmwareReasonStillDeprecates() {
        Firmware fw = new Firmware();
        fw.setFirmwareName("op");
        FirmwareVersion current  = version(fw, "1.2", "aa", 1);
        FirmwareVersion previous = version(fw, "1.1", "bb", 0);

        EventRegistry ev = runLateRollback("crashCount", current, previous);

        assertTrue(ev.getResultMessage().startsWith("Rollback tardio: v1.2 reiniciou por crash várias vezes seguidas. "));
        assertEquals(FirmwareStatus.DEPRECATED, current.getStatus());
    }

    @Test
    void bootloaderRollbackShowsCrashTypeAndDeprecates() {
        Firmware fw = new Firmware();
        fw.setFirmwareName("op");
        FirmwareVersion current  = version(fw, "1.2", "aa", 1);
        FirmwareVersion previous = version(fw, "1.1", "bb", 0);

        EventRegistry ev = runLateRollback("bootloader_rollback:TASK_WDT", current, previous);

        assertTrue(ev.getResultMessage().startsWith(
            "Rollback tardio: v1.2 não iniciou corretamente após o OTA (TASK_WDT). "));
        assertEquals(FirmwareStatus.DEPRECATED, current.getStatus());
    }

    @Test
    void unknownReasonKeepsVersion() {
        Firmware fw = new Firmware();
        fw.setFirmwareName("op");
        FirmwareVersion current  = version(fw, "1.2", "aa", 1);
        FirmwareVersion previous = version(fw, "1.1", "bb", 0);

        EventRegistry ev = runLateRollback("unknown", current, previous);

        assertEquals("Rollback tardio: v1.2 falhou (motivo não informado pelo device). Device revertido para v1.1.",
            ev.getResultMessage());
        assertTrue(current.getStatus() != FirmwareStatus.DEPRECATED);
    }
}
