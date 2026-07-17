package com.eric.governanceApi.governanceApi.service;

import com.eric.governanceApi.governanceApi.audit.Auditable;
import com.eric.governanceApi.governanceApi.enums.AuditAction;
import com.eric.governanceApi.governanceApi.enums.ErrorCode;
import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareVersion;
import com.eric.governanceApi.governanceApi.model.entity.ProvisioningToken;
import com.eric.governanceApi.governanceApi.model.request.GenerateFlashPackageRequest;
import com.eric.governanceApi.governanceApi.model.request.RegisterDeviceRequest;
import com.eric.governanceApi.governanceApi.repository.FirmwareVersionRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Slf4j
public class FlashPackageService {

    private static final String PLATFORM_BOOTLOADER_KEY = "platform/bootloader.bin";
    private static final String PLATFORM_PARTITION_TABLE_KEY = "platform/partition-table.bin";

    @Value("${provisioning.nvs-python-path:python3}")
    private String nvsPythonPath;

    @Value("${provisioning.nvs-script-path:}")
    private String nvsScriptPath;

    @Value("${provisioning.nvs-partition-size:0x5000}")
    private String nvsPartitionSize;

    private final DeviceProvisioningService provisioningService;
    private final FirmwareVersionRepository firmwareVersionRepository;
    private final FirmwareStorageService storageService;


    public FlashPackageService(DeviceProvisioningService provisioningService,
                              FirmwareVersionRepository firmwareVersionRepository, FirmwareStorageService storageService) {
        this.provisioningService = provisioningService;
        this.firmwareVersionRepository = firmwareVersionRepository;
        this.storageService = storageService;
    }

    @Auditable(action = AuditAction.FLASH_PACKAGE_GENERATED, targetType = "DEVICE")
    public byte[] generatePackage(GenerateFlashPackageRequest request) throws IOException {

        Path tempDir = Files.createTempDirectory("flash_pkg_");
        try {
            // Valida e baixa todos os artefatos ANTES de criar device+token no banco:
            // falha de storage/firmware ausente não deixa PENDING órfão.
            FirmwareVersion firmware = resolveProvisioningFirmware();
            Path firmwarePath   = fetchFirmware(firmware, tempDir);
            Path bootloaderPath = fetchFromStorage(PLATFORM_BOOTLOADER_KEY, tempDir, "bootloader.bin");
            Path partTablePath  = fetchFromStorage(PLATFORM_PARTITION_TABLE_KEY, tempDir, "partition-table.bin");
            String firmwareLabel = firmwarePath.getFileName().toString();

            ProvisioningToken token = provisioningService.registerDevice(
                    new RegisterDeviceRequest(request.deviceName(), request.groupId())
            );
            String deviceId = token.getDevice().getDeviceId();

            log.info("Generating flash package for device '{}', token={}", request.deviceName(), token.getToken());

            try {
                Path csvPath    = tempDir.resolve("nvs_data.csv");
                Path nvsBinPath = tempDir.resolve("nvs_device.bin");

                writeCsv(csvPath, request, token.getToken(), deviceId, firmware.getVersion());
                generateNvsBin(csvPath, nvsBinPath);

                log.info("Package generated for '{}' — firmware='{}'", request.deviceName(), firmwareLabel);

                return buildZip(bootloaderPath, partTablePath, firmwarePath, firmwareLabel,
                        nvsBinPath, buildReadme(request.deviceName(), token.getToken(), firmwareLabel));
            } catch (IOException | RuntimeException e) {
                provisioningService.discardPendingDevice(deviceId);
                throw e;
            }

        } finally {
            deleteDirectory(tempDir);
        }
    }

    // -------------------------------------------------------------------------

    private FirmwareVersion resolveProvisioningFirmware() {
        return firmwareVersionRepository.findFirstByFirmware_ProvisioningFirmwareTrueOrderByUploadedAtDesc()
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.FIRMWARE_NOT_FOUND,
                    "No provisioning firmware registered. Upload a firmware with isProvisioning=true."));
    }

    private Path fetchFirmware(FirmwareVersion fw, Path tempDir) throws IOException {
       
        if (fw.getStatus() == FirmwareStatus.DEPRECATED) {
            throw new IllegalArgumentException(
                    "Firmware v" + fw.getVersion() + " is DEPRECATED and cant be used.");
        }

        return fetchFromStorage(fw.getFilename(), tempDir, fw.getFilename());
    }

    private Path fetchFromStorage(String key, Path tempDir, String localName) throws IOException {
        Path dest = tempDir.resolve(localName);
        try (InputStream in = storageService.open(key)) {
            Files.copy(in, dest);
        }
        return dest;
    }


    private void writeCsv(Path csvPath, GenerateFlashPackageRequest request, String token, String deviceId, String firmwareVersion) throws IOException {

        String csv = """
                key,type,encoding,value
                crypto_store,namespace,,
                wifi_ssid,data,string,%s
                wifi_pass,data,string,%s
                prov_token,data,string,%s
                device_id,data,string,%s
                main_store,namespace,,
                fw_version,data,string,%s
                """.formatted(request.wifiSsid(), request.wifiPass(), token, deviceId, firmwareVersion);

        log.info(csv);
        Files.writeString(csvPath, csv);
    }

    private void generateNvsBin(Path csvPath, Path outPath) throws IOException {
        List<String> cmd = buildNvsCommand(csvPath, outPath);
        log.info("Executing: {}", cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("NVS generation interrupted");
        }

        if (exitCode != 0) {
            throw new IOException("nvs_partition_gen failed (exit " + exitCode + "): " + output);
        }
    }

    private List<String> buildNvsCommand(Path csvPath, Path outPath) {
        List<String> cmd = new ArrayList<>();
        cmd.add(nvsPythonPath);
        if (!nvsScriptPath.isBlank()) {
            // Modo script: python3 /path/nvs_partition_gen.py generate ...
            cmd.add(nvsScriptPath);
        } else {
            // Modo módulo: python3 -m nvs_partition_gen generate ...
            // (requer: pip install esp-idf-nvs-partition-gen no venv)
            cmd.add("-m");
            cmd.add("nvs_partition_gen");
        }
        cmd.add("generate");
        cmd.add(csvPath.toString());
        cmd.add(outPath.toString());
        cmd.add(nvsPartitionSize);
        return cmd;
    }

    private byte[] buildZip(Path bootloaderPath, Path partTablePath,
                             Path firmwarePath, String firmwareLabel,
                             Path nvsBinPath, String readme) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            addFileToZip(zip, bootloaderPath,  "bootloader.bin");
            addFileToZip(zip, partTablePath,   "partition-table.bin");
            addFileToZip(zip, firmwarePath,    firmwareLabel);
            addFileToZip(zip, nvsBinPath,      "nvs_device.bin");
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write(readme.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return baos.toByteArray();
    }

    private void addFileToZip(ZipOutputStream zip, Path file, String entryName) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private String buildReadme(String deviceName, String token, String firmwareLabel) {
        return """
                Flash Package — %s
                ==========================================
                Token: %s
                Firmware: %s

                Dependencia: pip install esptool

                Comando de flash completo (substituir <PORT> pela porta serial):

                  python3 -m esptool --port <PORT> --baud 460800 write_flash \\
                    0x0     bootloader.bin \\
                    0x8000  partition-table.bin \\
                    0x9000  nvs_device.bin \\
                    0x10000 %s

                Enderecos de flash:
                  Bootloader       -> 0x0
                  Partition table  -> 0x8000
                  NVS              -> 0x9000
                  Firmware         -> 0x10000

                ATENCAO: nao use erase_flash antes deste comando.
                O write_flash apaga automaticamente apenas os setores necessarios.

                Apos o flash, o device ira:
                  1. Conectar ao WiFi automaticamente (sem captive portal)
                  2. Gerar chaves ECC localmente
                  3. Registrar-se no govApi usando o token acima
                  4. Aguardar comando OTA para receber firmware definitivo
                """.formatted(deviceName, token, firmwareLabel, firmwareLabel);
    }

    private void deleteDirectory(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }
}
