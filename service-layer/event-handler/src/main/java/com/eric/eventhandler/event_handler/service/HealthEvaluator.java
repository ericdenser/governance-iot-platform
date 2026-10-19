package com.eric.eventhandler.event_handler.service;

import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Saude do device a partir da telemetria. A histerese evita alternar
// WARNING/OK a cada leitura perto do limite.
@Component
public class HealthEvaluator {

    public static final String LOW_BATTERY = "LOW_BATTERY";
    public static final String HIGH_TEMP   = "HIGH_TEMP";

    @Value("${app.health.battery-low-mv:3500}")
    private double batteryLowMv;

    @Value("${app.health.battery-hysteresis-mv:100}")
    private double batteryHysteresisMv;

    @Value("${app.health.temp-high-c:70}")
    private double tempHighC;

    @Value("${app.health.temp-hysteresis-c:5}")
    private double tempHysteresisC;

    // Leitura ausente mantem o motivo como estava
    public Set<String> evaluate(Set<String> previous, Double batteryMv, Double tempC) {
        Set<String> reasons = new TreeSet<>(previous);

        // <= 0: device sem ADC de bateria
        if (batteryMv != null && batteryMv > 0) {
            if (batteryMv < batteryLowMv) reasons.add(LOW_BATTERY);
            else if (batteryMv >= batteryLowMv + batteryHysteresisMv) reasons.remove(LOW_BATTERY);
        }

        if (tempC != null) {
            if (tempC > tempHighC) reasons.add(HIGH_TEMP);
            else if (tempC <= tempHighC - tempHysteresisC) reasons.remove(HIGH_TEMP);
        }

        return reasons;
    }
}
