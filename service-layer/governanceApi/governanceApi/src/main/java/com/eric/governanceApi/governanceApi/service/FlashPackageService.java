package com.eric.governanceApi.governanceApi.service;

import com.eric.governanceApi.governanceApi.audit.Auditable;
import com.eric.governanceApi.governanceApi.enums.AuditAction;
import com.eric.governanceApi.governanceApi.enums.ErrorCode;
import com.eric.governanceApi.governanceApi.enums.TargetChip;
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

    // ESP8266 (core Arduino 3.1.2, Flash Size 4MB FS:2MB): inicio do LittleFS
    private static final String ESP8266_FS_OFFSET = "0x200000";

    // Erased otadata (0xe000, 8 KB). Without it a board that already ran an OTA keeps
    // booting the old ota_1 app and ignores the provisioning firmware written to ota_0.
    private static final int OTADATA_SIZE = 0x2000;
    private static final String LITTLEFS_SCRIPT_RESOURCE = "/scripts/gen_prov_littlefs.py";

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

    // deviceId goes back to the client so it can follow the activation
    public record GeneratedPackage(byte[] zip, String deviceId) {}

    @Auditable(action = AuditAction.FLASH_PACKAGE_GENERATED, targetType = "DEVICE")
    public GeneratedPackage generatePackage(GenerateFlashPackageRequest request) throws IOException {

        Path tempDir = Files.createTempDirectory("flash_pkg_");
        try {

            TargetChip chip = TargetChip.orDefault(request.chip());
            if (chip == TargetChip.ESP8266 && (request.wifiSsid() == null || request.wifiSsid().isBlank())) {
                throw new IllegalArgumentException("ESP8266 não tem captive portal: informe o WiFi.");
            }

            FirmwareVersion firmware = resolveProvisioningFirmware(chip);
            Path firmwarePath   = fetchFirmware(firmware, tempDir);
            String firmwareLabel = firmwarePath.getFileName().toString();

            ProvisioningToken token = provisioningService.registerDevice(
                    new RegisterDeviceRequest(request.deviceName(), request.groupId(), chip)
            );
            String deviceId = token.getDevice().getDeviceId();

            log.info("Generating {} flash package for device '{}', token={}", chip, request.deviceName(), token.getToken());

            try {
                byte[] zip = chip == TargetChip.ESP8266
                    ? buildEsp8266Package(request, token.getToken(), deviceId, firmware, firmwarePath, firmwareLabel, tempDir)
                    : buildEsp32Package(request, token.getToken(), deviceId, firmware, firmwarePath, firmwareLabel, tempDir);

                log.info("Package generated for '{}' — firmware='{}'", request.deviceName(), firmwareLabel);
                return new GeneratedPackage(zip, deviceId);
            } catch (IOException | RuntimeException e) {
                provisioningService.discardPendingDevice(deviceId);
                throw e;
            }

        } finally {
            deleteDirectory(tempDir);
        }
    }

    // -------------------------------------------------------------------------

    private byte[] buildEsp32Package(GenerateFlashPackageRequest request, String token, String deviceId,
                                     FirmwareVersion firmware, Path firmwarePath, String firmwareLabel,
                                     Path tempDir) throws IOException {
        Path bootloaderPath = fetchFromStorage(PLATFORM_BOOTLOADER_KEY, tempDir, "bootloader.bin");
        Path partTablePath  = fetchFromStorage(PLATFORM_PARTITION_TABLE_KEY, tempDir, "partition-table.bin");
        Path csvPath    = tempDir.resolve("nvs_data.csv");
        Path nvsBinPath = tempDir.resolve("nvs_device.bin");

        writeCsv(csvPath, request, token, deviceId, firmware.getVersion());
        generateNvsBin(csvPath, nvsBinPath);

        Path otadataPath = tempDir.resolve("ota_data_initial.bin");
        byte[] erased = new byte[OTADATA_SIZE];
        java.util.Arrays.fill(erased, (byte) 0xFF);
        Files.write(otadataPath, erased);

        return buildZip(List.of(
                new ZipItem(bootloaderPath, "bootloader.bin"),
                new ZipItem(partTablePath,  "partition-table.bin"),
                new ZipItem(otadataPath,    "ota_data_initial.bin"),
                new ZipItem(firmwarePath,   firmwareLabel),
                new ZipItem(nvsBinPath,     "nvs_device.bin")),
            buildReadme(request.deviceName(), token, firmwareLabel));
    }

    // ESP8266 nao tem NVS: os dados de provisioning vao num /prov.txt dentro de uma imagem LittleFS
    private byte[] buildEsp8266Package(GenerateFlashPackageRequest request, String token, String deviceId,
                                       FirmwareVersion firmware, Path firmwarePath, String firmwareLabel,
                                       Path tempDir) throws IOException {
        Path scriptPath = tempDir.resolve("gen_prov_littlefs.py");
        try (InputStream in = getClass().getResourceAsStream(LITTLEFS_SCRIPT_RESOURCE)) {
            if (in == null) throw new IOException("Recurso " + LITTLEFS_SCRIPT_RESOURCE + " não encontrado");
            Files.copy(in, scriptPath);
        }

        Path fsPath = tempDir.resolve("littlefs.bin");
        runPython(List.of(
                nvsPythonPath, scriptPath.toString(),
                "--ssid", request.wifiSsid(),
                "--password", request.wifiPass() != null ? request.wifiPass() : "",
                "--token", token,
                "--device-id", deviceId,
                "--fw-version", firmware.getVersion(),
                "--fw-sha", firmware.getSha256(),
                "-o", fsPath.toString()),
            "gen_prov_littlefs");

        return buildZip(List.of(
                new ZipItem(firmwarePath, firmwareLabel),
                new ZipItem(fsPath,       "littlefs.bin")),
            buildEsp8266Readme(request.deviceName(), token, firmwareLabel));
    }

    private FirmwareVersion resolveProvisioningFirmware(TargetChip chip) {
        return firmwareVersionRepository.findFirstByFirmware_ProvisioningFirmwareTrueAndFirmware_TargetChipOrderByUploadedAtDesc(chip)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.FIRMWARE_NOT_FOUND,
                    "No provisioning firmware registered for " + chip + ". Upload a firmware with isProvisioning=true."));
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

        Files.writeString(csvPath, csv);
    }

    private void generateNvsBin(Path csvPath, Path outPath) throws IOException {
        List<String> cmd = buildNvsCommand(csvPath, outPath);
        log.info("Executing: {}", cmd);
        runPython(cmd, "nvs_partition_gen");
    }

    private void runPython(List<String> cmd, String tool) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(tool + " interrupted");
        }

        if (exitCode != 0) {
            throw new IOException(tool + " failed (exit " + exitCode + "): " + output);
        }
    }

    private List<String> buildNvsCommand(Path csvPath, Path outPath) {
        List<String> cmd = new ArrayList<>();
        cmd.add(nvsPythonPath);
        if (!nvsScriptPath.isBlank()) {
            // Modo script: python3 /path/nvs_partition_gen.py generate ...
            cmd.add(nvsScriptPath);
        } else {
            // Modo módulo: python3 -m esp_idf_nvs_partition_gen generate ...
            // (requer: pip install esp-idf-nvs-partition-gen no venv)
            cmd.add("-m");
            cmd.add("esp_idf_nvs_partition_gen");
        }
        cmd.add("generate");
        cmd.add(csvPath.toString());
        cmd.add(outPath.toString());
        cmd.add(nvsPartitionSize);
        return cmd;
    }

    private record ZipItem(Path path, String entryName) {}

    private byte[] buildZip(List<ZipItem> files, String readme) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            for (ZipItem f : files) {
                addFileToZip(zip, f.path(), f.entryName());
            }
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
                    0xe000  ota_data_initial.bin \\
                    0x10000 %s

                Enderecos de flash:
                  Bootloader       -> 0x0
                  Partition table  -> 0x8000
                  NVS              -> 0x9000
                  OTA data         -> 0xe000  (zerado: garante boot no firmware gravado)
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

    private String buildEsp8266Readme(String deviceName, String token, String firmwareLabel) {
        return """
                Flash Package (ESP8266) — %s
                ==========================================
                Token: %s
                Firmware: %s

                Dependencia: pip install esptool

                Comando de flash completo (substituir <PORT> pela porta serial):

                  python3 -m esptool --chip esp8266 --port <PORT> --baud 460800 write_flash \\
                    0x0      %s \\
                    %s littlefs.bin

                Enderecos de flash:
                  Firmware (inclui bootloader) -> 0x0
                  LittleFS (/prov.txt)         -> %s

                O firmware precisa ter sido compilado com Flash Size 4MB (FS:2MB OTA:~1019KB).

                Apos o flash, o device ira:
                  1. Ler WiFi, token e device_id do /prov.txt
                  2. Registrar-se no govApi usando o token acima
                  3. Apagar o /prov.txt e seguir operando no mesmo firmware
                """.formatted(deviceName, token, firmwareLabel, firmwareLabel, ESP8266_FS_OFFSET, ESP8266_FS_OFFSET);
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
