package com.eric.governanceApi.governanceApi.service;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public interface FirmwareStorageService {

    // Upload of stream with known size
    void store(String key, InputStream data, long size);

    // Todos os objetos do bucket: key -> lastModified (integrity check / orphan cleanup)
    Map<String, Instant> listAllObjects();

    // Returns a presigned url for device ota
    String presignDownloadUrl(String key, Duration ttl);

    // True is object exist in bucket
    boolean exists(String key);

    // Remove object
    void delete(String key);

    // Stream do objeto para leitura interna
    InputStream open(String key);
}
