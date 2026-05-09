package com.eric.governanceApi.governanceApi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.eric.governanceApi.governanceApi.repository.DeviceRepository;

import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.springframework.web.client.RestClient;

import java.io.FileOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509CRL;
import java.util.List;

/**
 * Renova o CRL automaticamente 
 * Sem isso, se nenhum device for revogado dentro do período de
 * validade do CRL, o broker para de aceitar conexões
 */
@Slf4j
@Component
public class CrlScheduler {

    private final CryptoService cryptoService;
    private final DeviceRepository deviceRepository;
    private final RestClient restClient;

    @Value("${mqtt.crl-path}")
    private String crlPath;

    @Value("${infra.api-key}")
    private String infraApiKey;

    public CrlScheduler(CryptoService cryptoService,
                                DeviceRepository deviceRepository,
                                RestClient restClient) {
        this.cryptoService = cryptoService;
        this.deviceRepository = deviceRepository;
        this.restClient = restClient;
    }

    
    @Scheduled(cron = "0 0 3 1 */1 *")  
    public void renewCrl() {

        try {
            // Busca todos os certificados revogados no CMDB
            List<String> revokedCerts = deviceRepository.findAllRevokedCertificates();
            log.info("Certificados revogados encontrados: {}", revokedCerts.size());

            // Gera novo CRL assinado pela CA
            X509CRL crl = cryptoService.generateCRL(revokedCerts);

            // Salva no disco
            StringWriter sw = new StringWriter();
            try (PemWriter pw = new PemWriter(sw)) {
                pw.writeObject(new PemObject("X509 CRL", crl.getEncoded()));
            }
            try (FileOutputStream fos = new FileOutputStream(crlPath)) {
                fos.write(sw.toString().getBytes(StandardCharsets.UTF_8));
            }
            log.info("CRL salvo em {}. Válido até: {}", crlPath, crl.getNextUpdate());

            // Notifica o broker para recarregar
            try {
                restClient.post()
                    .uri("http://localhost:8089/reload-crl")
                    .header("X-API-Key", infraApiKey)
                    .retrieve()
                    .toBodilessEntity();
                log.info("Broker notificado — CRL recarregado com sucesso.");
            } catch (Exception e) {
                log.warn("Broker não pôde ser notificado (offline?): {}. " +
                         "O CRL será carregado no próximo restart do broker.", e.getMessage());
            }

            log.info("Renovação do CRL concluída com sucesso.");

        } catch (Exception e) {
            log.error("FALHA CRÍTICA na renovação automática do CRL!", e);
            // todo disparar um alerta 
        }
    }
}