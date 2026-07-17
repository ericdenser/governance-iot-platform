package com.eric.governanceApi.governanceApi.service.impl;

import java.io.InputStream;
import java.time.Duration;

import com.eric.governanceApi.governanceApi.enums.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.eric.governanceApi.governanceApi.exceptions.InfrastructureException;
import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.service.FirmwareStorageService;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Service
@Slf4j
public class S3FirmwareStorageService implements FirmwareStorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${minio.bucket}")
    private String bucket;

    public S3FirmwareStorageService(S3Client s3Client, S3Presigner s3Presigner) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
    }


    @Override
    public void store(String key, InputStream data, long size) {
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentLength(size)
                .contentType("application/octet-stream")
                .build();
        s3Client.putObject(req, RequestBody.fromInputStream(data, size));
        log.info("Uploaded firmware key={} size={}B bucket={}", key, size, bucket);
    }

    @Override
    public String presignDownloadUrl(String key, Duration ttl) {
        GetObjectRequest getReq = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build();
        GetObjectPresignRequest presignReq = GetObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .getObjectRequest(getReq)
            .build();
        return s3Presigner.presignGetObject(presignReq).url().toString();
    }

    @Override
    public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }
    

    @Override
    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        log.info("Deleted firmware key={} bucket={}", key, bucket);
    }

    @Override
    public InputStream open(String key) {
        try {
            return s3Client.getObject(GetObjectRequest.builder()
                           .bucket(bucket)
                            .key(key)
                            .build());
        } catch (NoSuchKeyException e) {
            throw new ResourceNotFoundException(ErrorCode.FIRMWARE_NOT_FOUND,
                    "Binário" + key + "not found in storage");
        } catch (SdkException e) {
            throw new InfrastructureException(ErrorCode.STORAGE_UNAVAILABLE,
                "Firmware storage unavaiable: " + e.getMessage()
            );
        }
    }
}
