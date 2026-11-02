package com.eric.governanceApi.governanceApi.model.entity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "firmware_sensor_config")
public class FirmwareSensorConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "firmware_version_id", nullable = false)
    @JsonIgnore
    private FirmwareVersion firmwareVersion;

    @ManyToOne
    @JoinColumn(name = "sensor_id")
    private Sensor sensor;

    // "SDA=8,SCL=9" (pin names as printed on the sensor module)
    @Column(name = "pins")
    private String pins;

    // "temperature,pressure"
    @Column(name = "metrics")
    private String metrics;

    public Map<String, Integer> getPinMap() {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (pins == null || pins.isBlank()) return out;
        for (String pair : pins.split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) out.put(kv[0], Integer.valueOf(kv[1]));
        }
        return out;
    }

    public void setPinMap(Map<String, Integer> map) {
        pins = map.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(","));
    }

    public List<String> getMetricList() {
        return metrics == null || metrics.isBlank() ? List.of() : List.of(metrics.split(","));
    }
}
