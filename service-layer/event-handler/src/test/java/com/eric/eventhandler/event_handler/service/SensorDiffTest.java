package com.eric.eventhandler.event_handler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SensorDiffTest {

    @Test
    void addedAndRemoved() {
        Map<String, Object> d = TransitionDetector.sensorDiff("bmp280,aht25,mq135", "bmp280,gps,mq135");
        assertEquals(List.of("gps"), d.get("added"));
        assertEquals(List.of("aht25"), d.get("removed"));
    }

    @Test
    void firstReportHasOnlyAdded() {
        Map<String, Object> d = TransitionDetector.sensorDiff(null, "bmp280, gps");
        assertEquals(List.of("bmp280", "gps"), d.get("added"));
        assertEquals(List.of(), d.get("removed"));
    }

    @Test
    void allRemoved() {
        Map<String, Object> d = TransitionDetector.sensorDiff("bmp280,gps", "");
        assertEquals(List.of(), d.get("added"));
        assertEquals(List.of("bmp280", "gps"), d.get("removed"));
    }

    @Test
    void orderOnlyChangeIsEmpty() {
        Map<String, Object> d = TransitionDetector.sensorDiff("gps,bmp280", "bmp280,gps");
        assertEquals(List.of(), d.get("added"));
        assertEquals(List.of(), d.get("removed"));
    }
}
