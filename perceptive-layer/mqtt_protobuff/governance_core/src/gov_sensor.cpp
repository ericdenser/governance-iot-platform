#include "gov_sensor.h"

#include <string.h>
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"

static const char* TAG = "GovSensor";

#define STALE_FACTOR      3
#define SAMPLER_TICK_MS   100
#define HOTPLUG_PERIOD_US (30LL * 1000000)

struct Slot {
    const gov_sensor_t* sensor;
    bool                active;
    int                 count;
    gov_reading_t       readings[GOV_SENSOR_MAX_READINGS];
    int64_t             sampled_us;   // 0 = never sampled
    int64_t             next_us;
};

static Slot              s_slots[GOV_SENSOR_MAX];
static int               s_count      = 0;
static bool              s_discovered = false;
static bool              s_sampler    = false;
static int64_t           s_next_rescan_us = 0;
static char              s_csv[GOV_SENSOR_MAX * 25] = "";
static SemaphoreHandle_t s_mutex      = nullptr;

static void lock()   { xSemaphoreTake(s_mutex, portMAX_DELAY); }
static void unlock() { xSemaphoreGive(s_mutex); }

static bool valid_code(const char* code) {
    if (code == nullptr) return false;
    size_t len = strlen(code);
    if (len == 0 || len > 24) return false;
    for (size_t i = 0; i < len; i++) {
        char c = code[i];
        if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) return false;
    }
    return true;
}

static Slot* find(const char* code) {
    for (int i = 0; i < s_count; i++) {
        if (strcmp(s_slots[i].sensor->code, code) == 0) return &s_slots[i];
    }
    return nullptr;
}

static void sample(Slot& slot, int64_t now_us) {
    gov_reading_t tmp[GOV_SENSOR_MAX_READINGS] = {};
    int n = slot.sensor->read ? slot.sensor->read(tmp, GOV_SENSOR_MAX_READINGS) : 0;
    if (n < 0) n = 0;
    if (n > GOV_SENSOR_MAX_READINGS) n = GOV_SENSOR_MAX_READINGS;

    lock();
    memcpy(slot.readings, tmp, sizeof(tmp));
    slot.count      = n;
    slot.sampled_us = now_us;
    unlock();
}

static bool is_stale(const Slot& slot, int64_t now_us) {
    if (slot.sampled_us == 0) return true;
    int64_t max_age_us = (int64_t)slot.sensor->sample_period_ms * 1000 * STALE_FACTOR;
    return now_us - slot.sampled_us > max_age_us;
}

void gov_sensors_sample_due(int64_t now_us) {
    for (int i = 0; i < s_count; i++) {
        Slot& slot = s_slots[i];
        if (!slot.active || slot.sensor->sample_period_ms == 0 || now_us < slot.next_us) continue;
        sample(slot, now_us);
        slot.next_us = now_us + (int64_t)slot.sensor->sample_period_ms * 1000;
    }
}

static void rebuild_csv() {
    lock();
    s_csv[0] = '\0';
    for (int i = 0; i < s_count; i++) {
        if (!s_slots[i].active) continue;
        if (s_csv[0] != '\0') strlcat(s_csv, ",", sizeof(s_csv));
        strlcat(s_csv, s_slots[i].sensor->code, sizeof(s_csv));
    }
    unlock();
}

// Runs init() and marks the slot active with an empty cache
static bool activate(Slot& slot, int64_t now_us) {
    const gov_sensor_t* s = slot.sensor;
    if (s->init != nullptr) {
        esp_err_t err = s->init();
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "[%s] init falhou: %s", s->code, esp_err_to_name(err));
            return false;
        }
    }
    lock();
    slot.active     = true;
    slot.count      = 0;
    slot.sampled_us = 0;
    slot.next_us    = now_us;
    unlock();
    return true;
}

static void deactivate(Slot& slot) {
    lock();
    slot.active     = false;
    slot.count      = 0;
    slot.sampled_us = 0;
    unlock();
}

bool gov_sensors_rescan(int64_t now_us) {
    bool changed = false;
    for (int i = 0; i < s_count; i++) {
        Slot& slot = s_slots[i];
        if (!slot.sensor->hotplug) continue;

        bool present = slot.sensor->probe();
        if (present && !slot.active && activate(slot, now_us)) {
            ESP_LOGI(TAG, "[%s] conectado", slot.sensor->code);
            changed = true;
        } else if (!present && slot.active) {
            deactivate(slot);
            ESP_LOGW(TAG, "[%s] desconectado", slot.sensor->code);
            changed = true;
        }
    }
    if (changed) rebuild_csv();
    return changed;
}

static void sampler_task(void*) {
    while (true) {
        int64_t now = esp_timer_get_time();
        gov_sensors_sample_due(now);
        if (now >= s_next_rescan_us) {
            gov_sensors_rescan(now);
            s_next_rescan_us = now + HOTPLUG_PERIOD_US;
        }
        vTaskDelay(pdMS_TO_TICKS(SAMPLER_TICK_MS));
    }
}

esp_err_t gov_sensor_register(const gov_sensor_t* sensor) {
    if (s_mutex == nullptr) s_mutex = xSemaphoreCreateMutex();

    if (sensor == nullptr || sensor->probe == nullptr || !valid_code(sensor->code)) {
        ESP_LOGE(TAG, "Sensor invalido: code [%s] (use [a-z0-9_]{1,24}) ou sem probe",
                 sensor && sensor->code ? sensor->code : "null");
        return ESP_ERR_INVALID_ARG;
    }
    if (find(sensor->code) != nullptr) {
        ESP_LOGE(TAG, "Sensor [%s] registrado duas vezes", sensor->code);
        return ESP_ERR_INVALID_STATE;
    }
    if (s_count >= GOV_SENSOR_MAX) {
        ESP_LOGE(TAG, "Limite de %d sensores atingido, [%s] ignorado", GOV_SENSOR_MAX, sensor->code);
        return ESP_ERR_NO_MEM;
    }

    if (sensor->info == nullptr) {
        ESP_LOGW(TAG, "[%s] sem info: a plataforma nao vai listar pinos e metricas", sensor->code);
    }

    s_slots[s_count] = {};
    s_slots[s_count].sensor = sensor;
    s_count++;
    return ESP_OK;
}

int gov_sensor_count(void) {
    return s_count;
}

const char* gov_sensors_discover(void) {
    // runs once per boot; later changes come from gov_sensors_rescan()
    if (s_discovered) return s_csv;
    s_discovered = true;

    int64_t now = esp_timer_get_time();
    bool needs_sampler = false;

    for (int i = 0; i < s_count; i++) {
        Slot& slot = s_slots[i];
        const gov_sensor_t* s = slot.sensor;

        bool present = s->probe() && activate(slot, now);
        ESP_LOGI(TAG, "[%s] %s", s->code, present ? "detectado" : "ausente");
        if (s->hotplug || (present && s->sample_period_ms > 0)) needs_sampler = true;
    }
    rebuild_csv();

    if (needs_sampler && !s_sampler) {
        s_sampler = true;
        s_next_rescan_us = now + HOTPLUG_PERIOD_US;
        xTaskCreate(sampler_task, "gov_sensors", 4096, nullptr, 2, nullptr);
    }
    return s_csv;
}

void gov_sensors_active_csv(char* out, size_t len) {
    if (out == nullptr || len == 0) return;
    if (s_mutex == nullptr) { out[0] = '\0'; return; }
    lock();
    strlcpy(out, s_csv, len);
    unlock();
}

int gov_sensors_collect(sensor_reading_t* out, int max) {
    int n = 0;
    int64_t now = esp_timer_get_time();

    for (int i = 0; i < s_count && n < max; i++) {
        Slot& slot = s_slots[i];
        if (!slot.active) continue;

        if (slot.sensor->sample_period_ms == 0) sample(slot, now);
        else if (is_stale(slot, now)) continue;

        lock();
        for (int r = 0; r < slot.count && n < max; r++) {
            strlcpy(out[n].key, slot.readings[r].metric, sizeof(out[n].key));
            out[n].value = slot.readings[r].value;
            n++;
        }
        unlock();
    }
    return n;
}

bool gov_sensor_is_active(const char* code) {
    Slot* slot = find(code);
    return slot != nullptr && slot->active;
}

bool gov_sensor_get(const char* code, const char* metric, float* value, int64_t* age_ms) {
    Slot* slot = find(code);
    if (slot == nullptr || !slot->active || s_mutex == nullptr) return false;

    bool found = false;
    lock();
    if (slot->sampled_us != 0) {
        for (int r = 0; r < slot->count; r++) {
            if (strcmp(slot->readings[r].metric, metric) != 0) continue;
            if (value) *value = slot->readings[r].value;
            if (age_ms) *age_ms = (esp_timer_get_time() - slot->sampled_us) / 1000;
            found = true;
            break;
        }
    }
    unlock();
    return found;
}
