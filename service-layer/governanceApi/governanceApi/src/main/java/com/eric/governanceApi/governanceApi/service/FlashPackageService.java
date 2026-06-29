package com.eric.governanceApi.governanceApi.service;

import com.eric.governanceApi.governanceApi.audit.Auditable;
import com.eric.governanceApi.governanceApi.enums.AuditAction;
import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.model.entity.Firmware;
import com.eric.governanceApi.governanceApi.model.entity.ProvisioningToken;
import com.eric.governanceApi.governanceApi.model.request.GenerateFlashPackageRequest;
import com.eric.governanceApi.governanceApi.model.request.RegisterDeviceRequest;
import com.eric.governanceApi.governanceApi.repository.FirmwareRepository;
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

    @Value("${provisioning.nvs-python-path:python3}")
    private String nvsPythonPath;

    @Value("${provisioning.nvs-script-path:}")
    private String nvsScriptPath;

    @Value("${provisioning.nvs-partition-size:0x5000}")
    private String nvsPartitionSize;

    @Value("${provisioning.bootloader-bin-path:}")
    private String bootloaderBinPath;

    @Value("${provisioning.partition-table-bin-path:}")
    private String partitionTableBinPath;

    @Value("${ota.firmware-storage-path:}")
    private String firmwareStoragePath;

    @Value("${provisioning.token-ttl-seconds:86400}")
    private int tokenTtlSeconds;

    private final DeviceProvisioningService provisioningService;
    private final FirmwareRepository firmwareRepository;

    public FlashPackageService(DeviceProvisioningService provisioningService,
                               FirmwareRepository firmwareRepository) {
        this.provisioningService = provisioningService;
        this.firmwareRepository = firmwareRepository;
    }

    @Auditable(action = AuditAction.FLASH_PACKAGE_GENERATED, targetType = "DEVICE")
    public byte[] generatePackage(GenerateFlashPackageRequest request) throws IOException {
        ProvisioningToken token = provisioningService.registerDevice(
                new RegisterDeviceRequest(request.deviceName()), tokenTtlSeconds
        );

        String deviceId = token.getDevice().getDeviceId();

        log.info("Gerando pacote de flash para device '{}', token={}", request.deviceName(), token.getToken());

        Path tempDir = Files.createTempDirectory("flash_pkg_");
        try {

            Path csvPath    = tempDir.resolve("nvs_data.csv");
            Path nvsBinPath = tempDir.resolve("nvs_device.bin");

            Firmware firmware = resolveProvisioningFirmware();

            String firmwareVersion = firmware.getVersion();

            writeCsv(csvPath, request, token.getToken(), deviceId, firmwareVersion);
            generateNvsBin(csvPath, nvsBinPath);

            Path firmwarePath = resolveFirmwarePath(firmware);
            Path bootloaderPath = resolveRequiredBin(bootloaderBinPath, "bootloader");
            Path partTablePath  = resolveRequiredBin(partitionTableBinPath, "partition-table");
            String firmwareLabel = firmwarePath.getFileName().toString();

            log.info("Pacote gerado para '{}' — firmware='{}', bootloader e partition-table incluidos",
                    request.deviceName(), firmwareLabel);

            return buildZip(bootloaderPath, partTablePath, firmwarePath, firmwareLabel,
                    nvsBinPath, buildReadme(request.deviceName(), token.getToken(), firmwareLabel));

        } finally {
            deleteDirectory(tempDir);
        }
    }

    // -------------------------------------------------------------------------

    private Firmware resolveProvisioningFirmware() {
        return firmwareRepository.findByProvisioningFirmwareTrue()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Nenhum firmware de provisioning registrado. Faça upload de um firmware com isProvisioning=true."));
    }

    private Path resolveFirmwarePath(Firmware fw) throws IOException {
       
        if (fw.getStatus() == FirmwareStatus.DEPRECATED) {
            throw new IllegalArgumentException(
                    "Firmware v" + fw.getVersion() + " esta DEPRECATED e nao pode ser usado.");
        }

        Path path = Path.of(firmwareStoragePath, fw.getFilename());
        
        if (!Files.exists(path)) {
            throw new FileNotFoundException(
                    "Arquivo do firmware v" + fw.getVersion() + " nao encontrado em disco: " + path);
        }
        return path;
    }

    private Path resolveRequiredBin(String configuredPath, String label) throws IOException {
        if (configuredPath.isBlank()) {
            throw new IllegalStateException("provisioning." + label + "-bin-path nao configurado.");
        }
        Path path = Path.of(configuredPath);
        if (!Files.exists(path)) {
            throw new FileNotFoundException(label + " nao encontrado em: " + configuredPath);
        }
        return path;
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
        log.info("Executando: {}", cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Geracao do NVS interrompida");
        }

        if (exitCode != 0) {
            throw new IOException("nvs_partition_gen falhou (exit " + exitCode + "): " + output);
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
