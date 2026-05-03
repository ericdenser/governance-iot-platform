package com.eric.governanceApi.governanceApi.service;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CRLHolder;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.bouncycastle.util.io.pem.PemWriter;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import java.util.List;

import jakarta.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Date;

@Service
public class CryptoService {

    @Value("classpath:certs/rootCA.p12")
    private Resource rootCaFile;

    @Value("${CA_PASSWORD}")
    private String CA_PASSWORD; 

    private KeyPair caKeyPair;
    private X509Certificate caCert;

    // WRAPPER PARA O RETORNO
    public static class SignedCertificateData {
        public final String pemString;
        public final X509Certificate certificateObj;

        public SignedCertificateData(String pemString, X509Certificate certificateObj) {
            this.pemString = pemString;
            this.certificateObj = certificateObj;
        }
    }

    @PostConstruct
    public void init() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        loadCA();
    }

    private void loadCA() throws Exception {
        System.out.println("🔐 A carregar Root CA offline do disco...");
        KeyStore ks = KeyStore.getInstance("PKCS12", "BC");
        try (FileInputStream fis = new FileInputStream(rootCaFile.getFile())) {
            ks.load(fis, CA_PASSWORD.toCharArray());
        }
        String alias = ks.aliases().nextElement();
        caCert = (X509Certificate) ks.getCertificate(alias);
        PrivateKey caPrivateKey = (PrivateKey) ks.getKey(alias, CA_PASSWORD.toCharArray());
        PublicKey caPublicKey = caCert.getPublicKey();
        caKeyPair = new KeyPair(caPublicKey, caPrivateKey);
        System.out.println("Root CA carregada com sucesso! Issuer: " + caCert.getSubjectX500Principal().getName());
    }

    // Assina o CSR e devolve o PEM e o objeto X509Certificate.
    public SignedCertificateData signDeviceCSR(String pemCsr, String macAddress) throws Exception {
        System.out.println("A processar e assinar CSR para o MAC: " + macAddress);
        
        PemReader pemReader = new PemReader(new StringReader(pemCsr));
        PemObject pemObject = pemReader.readPemObject();
        pemReader.close();

        if (pemObject == null) {
            throw new IllegalArgumentException("O CSR enviado é inválido. Certifique-se de usar o formato PEM com quebras de linha (\\n).");
        }

        PKCS10CertificationRequest csr = new PKCS10CertificationRequest(pemObject.getContent());
        
        PublicKey devicePublicKey = new JcaPEMKeyConverter().setProvider("BC").getPublicKey(csr.getSubjectPublicKeyInfo());

        X500Name issuer = X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded());
        X500Name subject = new X500Name("CN=" + macAddress + ", O=Mackenzie IoT Devices"); 
        
        long now = System.currentTimeMillis();
        Date startDate = new Date(now - 600000L);
        Date endDate = new Date(now + 365L * 24 * 60 * 60 * 1000); // Válido por 1 ano
        BigInteger serialNumber = BigInteger.valueOf(new SecureRandom().nextLong()).abs();

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer, serialNumber, startDate, endDate, subject, devicePublicKey
        );


        JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();

        // 1. Identificadores 
        certBuilder.addExtension(Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(caCert));
        certBuilder.addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(devicePublicKey));

        // 2. Restrição Básica 
        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));

        // 3. Key Usage (Libera a assinatura do Handshake TLS)
        certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));

        // 4. Extended Key Usage (Libera o login do Cliente no Mosquitto)
        certBuilder.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(caKeyPair.getPrivate());
        X509CertificateHolder certHolder = certBuilder.build(signer);
        
        // Converte o Holder do BouncyCastle de volta para o formato padrão X509Certificate do Java
        X509Certificate finalCert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(certHolder);

        StringWriter stringWriter = new StringWriter();
        PemWriter pemWriter = new PemWriter(stringWriter);
        pemWriter.writeObject(new PemObject("CERTIFICATE", certHolder.getEncoded()));
        pemWriter.close();

        return new SignedCertificateData(stringWriter.toString(), finalCert);
    }

    public X509CRL generateCRL(List<String> revokedPems) throws Exception {
        System.out.println("Gerando nova Lista de Revogação (CRL)...");

        X500Name issuer = X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded());
        Date now = new Date();
        // A lista é válida por 180 dias, mas geraremos uma nova sempre que um novo dispositivo for bloqueado
        Date nextUpdate = new Date(now.getTime() + 180L * 24 * 60 * 60 * 1000); 

        X509v2CRLBuilder crlBuilder = new X509v2CRLBuilder(issuer, now);
        crlBuilder.setNextUpdate(nextUpdate);

        // Para cada certificado revogado no CMDB, extraímos o número de série e colocamos na lista
        for (String pem : revokedPems) {
            PemReader pemReader = new PemReader(new StringReader(pem));
            X509CertificateHolder certHolder = new X509CertificateHolder(pemReader.readPemObject().getContent());
            // O Serial Number é o que o Mosquitto usa para banir
            crlBuilder.addCRLEntry(certHolder.getSerialNumber(), now, CRLReason.privilegeWithdrawn); 
            pemReader.close();
        }

        // O Cartório (Root CA) assina a Lista 
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(caKeyPair.getPrivate());
        X509CRLHolder crlHolder = crlBuilder.build(signer);
    
        System.out.println("Arquivo CRL gerado com sucesso");

        return new JcaX509CRLConverter().setProvider("BC").getCRL(crlHolder);
    }
}