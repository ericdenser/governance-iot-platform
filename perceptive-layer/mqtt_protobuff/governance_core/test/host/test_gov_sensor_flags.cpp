// Registration flags (fresh kernel state): ./run.sh
#include <stdio.h>
#include <string.h>
#include "gov_sensor.h"

int64_t g_fake_now_us = 0;
int g_tasks_created = 0;

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); failures++; } } while (0)

static bool probe_yes(void) { return true; }
static int read_raw(gov_reading_t* out, int) { out[0] = { "temperature", 25.0f }; return 1; }
static int read_fused(gov_reading_t* out, int) {
    float t = 0;
    if (!gov_sensor_get("raw_t", "temperature", &t, nullptr)) return 0;
    out[0] = { "temperature", t + 1.0f };
    return 1;
}
static int read_norm(gov_reading_t* out, int) { out[0] = { "rssi_x", 3.0f }; return 1; }

static const gov_sensor_t raw     = { "raw_t", 1000, probe_yes, nullptr, read_raw };
static const gov_sensor_t normal  = { "normal", 1000, probe_yes, nullptr, read_norm };
static const gov_sensor_t derived = { "fused", 1000, probe_yes, nullptr, read_fused };

int main() {
    CHECK(gov_sensor_register_flags(&raw, GOV_SENSOR_NO_TELEMETRY) == ESP_OK);
    CHECK(gov_sensor_register(&normal) == ESP_OK);
    CHECK(gov_sensor_register_flags(&derived, GOV_SENSOR_DERIVED) == ESP_OK);

    // derived is not hardware: left out of the reported list
    CHECK(strcmp(gov_sensors_discover(), "raw_t,normal") == 0);

    // registration order: raw is sampled before the derived one reads it
    g_fake_now_us = 1000;
    gov_sensors_sample_due(g_fake_now_us);

    sensor_reading_t out[16] = {};
    int n = gov_sensors_collect(out, 16);
    CHECK(n == 2);
    CHECK(strcmp(out[0].key, "rssi_x") == 0);
    CHECK(strcmp(out[1].key, "temperature") == 0 && out[1].value == 26.0f);

    // raw value still available to other code (UI, fusion)
    float v = 0;
    CHECK(gov_sensor_get("raw_t", "temperature", &v, nullptr) && v == 25.0f);

    printf("%s (%d failure(s))\n", failures ? "FAILED" : "OK", failures);
    return failures ? 1 : 0;
}
