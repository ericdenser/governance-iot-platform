#include "gov_sensor_flow.h"

#include "sdkconfig.h"
#include "driver/gpio.h"
#include "esp_attr.h"
#include "esp_timer.h"

#if CONFIG_GOV_SENSOR_FLOW

#define FLOW_GPIO         ((gpio_num_t)CONFIG_GOV_SENSOR_FLOW_GPIO)
#define FLOW_K            (CONFIG_GOV_SENSOR_FLOW_K_X100 / 100.0f)   // Hz per L/min
#define FLOW_MIN_PULSE_US 1000                                       // closer edges are noise (YF-B6 max ~200 Hz)

// written only by the ISR; 32-bit reads are atomic
static volatile uint32_t s_pulses = 0;
static int64_t           s_last_edge_us = 0;

static uint32_t s_prev_pulses = 0;
static int64_t  s_prev_us     = 0;
static bool     s_isr_service = false;

static void IRAM_ATTR flow_isr(void*) {
    int64_t now = esp_timer_get_time();
    if (now - s_last_edge_us < FLOW_MIN_PULSE_US) return;
    s_last_edge_us = now;
    s_pulses = s_pulses + 1;
}

// A pulse output cannot be detected without flow
static bool probe(void) {
    return GPIO_IS_VALID_GPIO(FLOW_GPIO);
}

static esp_err_t init(void) {
    gpio_config_t io = {};
    io.pin_bit_mask = 1ULL << FLOW_GPIO;
    io.mode         = GPIO_MODE_INPUT;
    io.pull_up_en   = GPIO_PULLUP_ENABLE;
    io.intr_type    = GPIO_INTR_POSEDGE;
    esp_err_t err = gpio_config(&io);
    if (err != ESP_OK) return err;

    if (!s_isr_service) {
        err = gpio_install_isr_service(0);
        if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) return err;   // INVALID_STATE = already installed
        s_isr_service = true;
    }

    s_prev_pulses = s_pulses;
    s_prev_us     = esp_timer_get_time();
    return gpio_isr_handler_add(FLOW_GPIO, flow_isr, nullptr);
}

// Read once per telemetry (sample_period_ms = 0), so flow_lpm is the average of that interval
static int read(gov_reading_t* out, int max) {
    if (max < 2) return 0;

    uint32_t pulses = s_pulses;
    int64_t  now    = esp_timer_get_time();
    float    dt_s   = (now - s_prev_us) / 1e6f;
    if (dt_s <= 0.0f) return 0;

    float hz = (uint32_t)(pulses - s_prev_pulses) / dt_s;
    s_prev_pulses = pulses;
    s_prev_us     = now;

    out[0] = { "flow_lpm", hz / FLOW_K };
    out[1] = { "volume_l", pulses / (FLOW_K * 60.0f) };
    return 2;
}

const gov_sensor_t gov_sensor_flow = {
    .code             = "flow",
    .sample_period_ms = 0,
    .probe            = probe,
    .init             = init,
    .read             = read,
    .info             = GOV_SENSOR_INFO("{\"code\":\"flow\",\"pins\":{\"SIG\":" GOV_STR(CONFIG_GOV_SENSOR_FLOW_GPIO) "},"
                                        "\"metrics\":[\"flow_lpm\",\"volume_l\"]}"),
    .hotplug          = false,
};

#endif
