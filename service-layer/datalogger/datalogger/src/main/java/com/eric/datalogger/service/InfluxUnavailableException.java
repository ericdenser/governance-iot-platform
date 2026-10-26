package com.eric.datalogger.service;

// Influx fora do ar ou recusando por config (rede, 5xx, 401/403).
public class InfluxUnavailableException extends RuntimeException {

    public InfluxUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
