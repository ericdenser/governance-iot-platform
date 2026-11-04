#include "TelemetryBuffer.h"

#include "esp_log.h"
#include "esp_heap_caps.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "SlotRing.h"

static const char* TAG = "TelemetryBuffer";

#define INTERNAL_MAX_SLOTS 30

typedef SlotRing<TelemetryBuffer::SLOT_BYTES> Ring;

static Ring              s_ring;
static SemaphoreHandle_t s_mutex = nullptr;

namespace TelemetryBuffer {

bool init(size_t capacity) {
    if (s_mutex != nullptr) return true;
    s_mutex = xSemaphoreCreateMutex();

    Ring::Slot* storage = (Ring::Slot*)heap_caps_calloc(capacity, sizeof(Ring::Slot), MALLOC_CAP_SPIRAM);
    if (storage == nullptr) {
        capacity = capacity < INTERNAL_MAX_SLOTS ? capacity : INTERNAL_MAX_SLOTS;
        storage = (Ring::Slot*)heap_caps_calloc(capacity, sizeof(Ring::Slot), MALLOC_CAP_8BIT);
        if (storage == nullptr) {
            ESP_LOGE(TAG, "Sem memoria para o buffer de telemetria");
            return false;
        }
        ESP_LOGW(TAG, "Sem PSRAM: buffer reduzido para %u leituras", (unsigned)capacity);
    }
    s_ring.attach(storage, capacity);
    ESP_LOGI(TAG, "Buffer de telemetria: %u leituras (%u KB)",
             (unsigned)capacity, (unsigned)(capacity * sizeof(Ring::Slot) / 1024));
    return true;
}

bool push(const uint8_t* data, size_t len) {
    if (s_mutex == nullptr) return false;
    xSemaphoreTake(s_mutex, portMAX_DELAY);
    uint32_t before = s_ring.dropped();
    bool ok = s_ring.push(data, len);
    bool overwrote = s_ring.dropped() != before;
    xSemaphoreGive(s_mutex);
    if (overwrote) ESP_LOGW(TAG, "Buffer cheio: leitura mais antiga descartada (%u no total)", (unsigned)dropped());
    return ok;
}

bool peek(uint8_t* out, size_t* len, uint32_t* seq) {
    if (s_mutex == nullptr) return false;
    xSemaphoreTake(s_mutex, portMAX_DELAY);
    bool ok = s_ring.peek(out, len, seq);
    xSemaphoreGive(s_mutex);
    return ok;
}

void pop(uint32_t seq) {
    if (s_mutex == nullptr) return;
    xSemaphoreTake(s_mutex, portMAX_DELAY);
    s_ring.pop(seq);
    xSemaphoreGive(s_mutex);
}

size_t count() {
    if (s_mutex == nullptr) return 0;
    xSemaphoreTake(s_mutex, portMAX_DELAY);
    size_t n = s_ring.count();
    xSemaphoreGive(s_mutex);
    return n;
}

size_t capacity() { return s_ring.capacity(); }

uint32_t dropped() { return s_ring.dropped(); }

}  // namespace TelemetryBuffer
