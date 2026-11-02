#include "gov_sensor_gps.h"

#include "sdkconfig.h"
#include "esp_log.h"
#include "driver/gpio.h"
#include "driver/uart.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "GpsManager.h"

static const char* TAG = "gps";

#define GPS_PORT    ((uart_port_t)CONFIG_GOV_GPS_UART_PORT)
#define GPS_TX_PIN  CONFIG_GOV_GPS_UART_TX_PIN
#define GPS_RX_PIN  CONFIG_GOV_GPS_UART_RX_PIN
#define POWER_ON_MS 500

static GpsManager s_gps;

void gov_gps_power(bool on) {
    static bool configured = false;
    if (!configured) {
        gpio_reset_pin((gpio_num_t)CONFIG_GOV_GPS_POWER_PIN);
        gpio_set_direction((gpio_num_t)CONFIG_GOV_GPS_POWER_PIN, GPIO_MODE_OUTPUT);
        configured = true;
    }
    gpio_set_level((gpio_num_t)CONFIG_GOV_GPS_POWER_PIN, on ? 1 : 0);
}

// Any NMEA sentence ('$') within 2 s
static bool uart_has_nmea(void) {
    uart_config_t cfg = {};
    cfg.baud_rate  = 9600;
    cfg.data_bits  = UART_DATA_8_BITS;
    cfg.parity     = UART_PARITY_DISABLE;
    cfg.stop_bits  = UART_STOP_BITS_1;
    cfg.flow_ctrl  = UART_HW_FLOWCTRL_DISABLE;
    cfg.source_clk = UART_SCLK_DEFAULT;

    if (uart_driver_install(GPS_PORT, 256, 0, 0, NULL, 0) != ESP_OK) {
        ESP_LOGE(TAG, "Falha ao instalar driver UART para probe GPS");
        return false;
    }
    uart_param_config(GPS_PORT, &cfg);
    uart_set_pin(GPS_PORT, GPS_TX_PIN, GPS_RX_PIN, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE);

    uint8_t buf[64];
    bool found = false;
    for (int i = 0; i < 20 && !found; i++) {
        int len = uart_read_bytes(GPS_PORT, buf, sizeof(buf), pdMS_TO_TICKS(100));
        for (int j = 0; j < len; j++) {
            if (buf[j] == '$') { found = true; break; }
        }
    }
    uart_driver_delete(GPS_PORT);
    return found;
}

static bool probe(void) {
    gov_gps_power(true);
    vTaskDelay(pdMS_TO_TICKS(POWER_ON_MS));
    ESP_LOGI(TAG, "Verificando presença do GPS (RX:%d TX:%d)...", GPS_RX_PIN, GPS_TX_PIN);
    bool found = uart_has_nmea();
    if (!found) gov_gps_power(false);
    return found;
}

static esp_err_t init(void) {
    s_gps.init(GPS_TX_PIN, GPS_RX_PIN, GPS_PORT);
    if (xTaskCreate(GpsManager::taskWrapper, "gps", 4096, &s_gps, 3, NULL) != pdPASS) return ESP_ERR_NO_MEM;
    return ESP_OK;
}

static int read(gov_reading_t* out, int max) {
    if (max < 3 || !s_gps.hasFix()) return 0;
    out[0] = { "latitude", s_gps.getLat() };
    out[1] = { "longitude", s_gps.getLon() };
    out[2] = { "altitude", s_gps.getAlt() };
    return 3;
}

time_t gov_gps_wait_time(uint32_t timeout_ms) {
    gov_gps_power(true);
    vTaskDelay(pdMS_TO_TICKS(POWER_ON_MS));

    GpsFix fix = {};
    time_t result = 0;
    if (GpsManager::waitForFix(GPS_TX_PIN, GPS_RX_PIN, GPS_PORT, fix, (int)timeout_ms)) {
        struct tm ti = fix.time;
        ti.tm_isdst = 0;
        result = mktime(&ti);
    }
    // discovery powers it on again if the GPS is used as a sensor
    gov_gps_power(false);
    return result;
}

const gov_sensor_t gov_sensor_gps = {
    .code             = "gps",
    .sample_period_ms = 5000,
    .probe            = probe,
    .init             = init,
    .read             = read,
    // TX/RX as printed on the GPS module: its TX goes to the ESP RX pin
    .info             = GOV_SENSOR_INFO("{\"code\":\"gps\",\"pins\":{\"TX\":" GOV_STR(CONFIG_GOV_GPS_UART_RX_PIN)
                                        ",\"RX\":" GOV_STR(CONFIG_GOV_GPS_UART_TX_PIN)
                                        ",\"POWER\":" GOV_STR(CONFIG_GOV_GPS_POWER_PIN) "},"
                                        "\"metrics\":[\"latitude\",\"longitude\",\"altitude\"]}"),
    // probe reinstalls the UART the GPS task is using
    .hotplug          = false,
};
