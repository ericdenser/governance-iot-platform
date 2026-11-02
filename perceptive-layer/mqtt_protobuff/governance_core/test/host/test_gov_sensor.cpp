// Host test of the sensor kernel: ./run.sh
#include <stdio.h>
#include <string.h>
#include "gov_sensor.h"

int64_t g_fake_now_us = 0;
int g_tasks_created = 0;

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); failures++; } } while (0)

static int reads_a = 0, inits_a = 0;
static bool probe_yes(void) { return true; }
static bool probe_no(void) { return false; }
static esp_err_t init_a(void) { inits_a++; return ESP_OK; }
static esp_err_t init_fail(void) { return ESP_FAIL; }
static int read_a(gov_reading_t* out, int) {
    reads_a++;
    out[0] = { "temperature", 21.5f };
    out[1] = { "pressure", 1013.0f };
    return 2;
}
static int read_now(gov_reading_t* out, int) { out[0] = { "level", 7.0f }; return 1; }

static const gov_sensor_t sensor_a      = { "fake_a", 1000, probe_yes, init_a, read_a };
static const gov_sensor_t sensor_absent = { "fake_b", 1000, probe_no, nullptr, read_a };
static const gov_sensor_t sensor_broken = { "fake_c", 1000, probe_yes, init_fail, read_a };
static const gov_sensor_t sensor_now    = { "fake_d", 0, probe_yes, nullptr, read_now };
static const gov_sensor_t sensor_flag   = { "fake_e", 0, probe_yes, nullptr, nullptr };
static const gov_sensor_t bad_code      = { "Fake-X", 0, probe_yes, nullptr, nullptr };
static const gov_sensor_t no_probe      = { "fake_f", 0, nullptr, nullptr, nullptr };

int main() {
    // registration rules
    CHECK(gov_sensor_register(&bad_code) == ESP_ERR_INVALID_ARG);
    CHECK(gov_sensor_register(&no_probe) == ESP_ERR_INVALID_ARG);
    CHECK(gov_sensor_register(&sensor_a) == ESP_OK);
    CHECK(gov_sensor_register(&sensor_a) == ESP_ERR_INVALID_STATE);
    CHECK(gov_sensor_register(&sensor_absent) == ESP_OK);
    CHECK(gov_sensor_register(&sensor_broken) == ESP_OK);
    CHECK(gov_sensor_register(&sensor_now) == ESP_OK);
    CHECK(gov_sensor_register(&sensor_flag) == ESP_OK);
    CHECK(gov_sensor_count() == 5);

    // discovery: absent and failed-init sensors are left out, init runs once
    const char* csv = gov_sensors_discover();
    CHECK(strcmp(csv, "fake_a,fake_d,fake_e") == 0);
    CHECK(inits_a == 1);
    CHECK(g_tasks_created == 1);
    gov_sensors_discover();
    CHECK(inits_a == 1);
    CHECK(gov_sensor_is_active("fake_a"));
    CHECK(!gov_sensor_is_active("fake_b"));
    CHECK(!gov_sensor_is_active("fake_c"));

    // nothing sampled yet: periodic sensor is skipped, period 0 is read on collect
    sensor_reading_t out[16] = {};
    int n = gov_sensors_collect(out, 16);
    CHECK(n == 1 && strcmp(out[0].key, "level") == 0 && out[0].value == 7.0f);

    // sampler runs the due sensor, then waits for its period
    g_fake_now_us = 1000;
    gov_sensors_sample_due(g_fake_now_us);
    CHECK(reads_a == 1);
    g_fake_now_us = 500000;
    gov_sensors_sample_due(g_fake_now_us);
    CHECK(reads_a == 1);
    g_fake_now_us = 1001000;
    gov_sensors_sample_due(g_fake_now_us);
    CHECK(reads_a == 2);

    memset(out, 0, sizeof(out));
    n = gov_sensors_collect(out, 16);
    CHECK(n == 3);
    CHECK(strcmp(out[0].key, "temperature") == 0 && out[0].value == 21.5f);
    CHECK(strcmp(out[1].key, "pressure") == 0);
    CHECK(strcmp(out[2].key, "level") == 0);

    // max is respected
    memset(out, 0, sizeof(out));
    CHECK(gov_sensors_collect(out, 2) == 2);

    // cached value lookup
    float v = 0; int64_t age = -1;
    g_fake_now_us = 1201000;
    CHECK(gov_sensor_get("fake_a", "pressure", &v, &age) && v == 1013.0f && age == 200);
    CHECK(!gov_sensor_get("fake_a", "humidity", &v, &age));
    CHECK(!gov_sensor_get("fake_b", "temperature", &v, &age));

    // stale cache (more than 3 periods without sampling) is not sent
    g_fake_now_us = 1001000 + 3000001;
    memset(out, 0, sizeof(out));
    n = gov_sensors_collect(out, 16);
    CHECK(n == 1 && strcmp(out[0].key, "level") == 0);

    printf("%s (%d failure(s))\n", failures ? "FAILED" : "OK", failures);
    return failures ? 1 : 0;
}
