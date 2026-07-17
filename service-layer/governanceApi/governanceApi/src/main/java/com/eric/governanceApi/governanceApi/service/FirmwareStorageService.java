package com.eric.governanceApi.governanceApi.service;

import java.io.InputStream;
import java.time.Duration;

public interface FirmwareStorageService {
    
    // Upload of stream with known size
    void store(String key, InputStream data, long size);

    // Returns a presigned url for device ota
    String presignDownloadUrl(String key, Duration ttl);

    // True is object exist in bucket
    boolean exists(String key);

    // Remove object
    void delete(String key);

    // Stream do objeto para leitura interna
    InputStream open(String key);
}
