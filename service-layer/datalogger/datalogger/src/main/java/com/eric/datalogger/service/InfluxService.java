package com.eric.datalogger.service;

import com.eric.datalogger.model.DataDTO;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.exceptions.InfluxException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;

@Service
public class InfluxService {

    @Value("${influxdb.url}")
    private String url;

    @Value("${influxdb.token}")
    private String token;

    @Value("${influxdb.org}")
    private String org;

    @Value("${influxdb.bucket}")
    private String bucket;

    private InfluxDBClient influxDBClient;
    private WriteApiBlocking writeApi;

    @SuppressWarnings("null")
    @PostConstruct
    public void init() {
        // Inicializa o cliente 
        influxDBClient = InfluxDBClientFactory.create(url, token.toCharArray(), org, bucket);
        writeApi = influxDBClient.getWriteApiBlocking();
        System.out.println("Ligação ao InfluxDB estabelecida!");
    }

    @PreDestroy
    public void close() {
        // Fecha a ligação quando a aplicação for encerrada
        if (influxDBClient != null) {
            influxDBClient.close();
        }
    }

    public void writePoint(DataDTO dto) {
        
        try {
            Point point = Point.measurement("sensor_data")
                .addTag("mac_address", dto.tag()) 
                .addField("temperatura", dto.data())  
                .time(Instant.now(), WritePrecision.NS); // Timestamp exato da gravação

            writeApi.writePoint(point);
            System.out.println("Dado guardado no InfluxDB: TAG=" + dto.tag() + " | Data=" + dto.data());
        } catch(InfluxException e) {
            throw new RuntimeException(e);
        }
    }
}