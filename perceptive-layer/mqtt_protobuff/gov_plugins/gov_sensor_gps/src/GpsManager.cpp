#include "GpsManager.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include <cstring>
#include <stdlib.h>

extern "C" {
    #include "minmea.h"
}

static const char* TAG = "GpsManager";

// ---- Helpers internos --------------------------------------------------------

static void uart_configure(uart_port_t port, int tx_pin, int rx_pin) {
    uart_config_t cfg = {
        .baud_rate  = 9600,
        .data_bits  = UART_DATA_8_BITS,
        .parity     = UART_PARITY_DISABLE,
        .stop_bits  = UART_STOP_BITS_1,
        .flow_ctrl  = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };
    uart_param_config(port, &cfg);
    uart_set_pin(port, tx_pin, rx_pin, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE);
}

// ---- Construtor --------------------------------------------------------------

GpsManager::GpsManager()
    : _uart_port(UART_NUM_1), _isInitialized(false),
      _latitude(0.0f), _longitude(0.0f), _altitude(0.0f), _hasFix(false) {}

// ---- init --------------------------------------------------------------------

void GpsManager::init(int tx_pin, int rx_pin, uart_port_t port) {
    _uart_port = port;
    ESP_ERROR_CHECK(uart_driver_install(_uart_port, GPS_BUF_SIZE * 2, 0, 0, NULL, 0));
    uart_configure(_uart_port, tx_pin, rx_pin);
    _isInitialized = true;
    ESP_LOGI(TAG, "GPS inicializado na UART%d (TX:%d RX:%d)", port, tx_pin, rx_pin);
}

// ---- Getters -----------------------------------------------------------------

float GpsManager::getLat() { return _latitude;  }
float GpsManager::getLon() { return _longitude; }
float GpsManager::getAlt() { return _altitude;  }
bool  GpsManager::hasFix() { return _hasFix;    }

// ---- waitForFix (one-shot) ---------------------------------------------------

bool GpsManager::waitForFix(int tx_pin, int rx_pin, uart_port_t port,
                             GpsFix& out, int timeout_ms) {
    if (uart_driver_install(port, GPS_BUF_SIZE, 0, 0, NULL, 0) != ESP_OK) {
        ESP_LOGE(TAG, "waitForFix: falha ao instalar driver UART");
        return false;
    }
    uart_configure(port, tx_pin, rx_pin);

    uint8_t* data = (uint8_t*) malloc(GPS_BUF_SIZE);
    if (!data) {
        ESP_LOGE(TAG, "waitForFix: sem RAM para o buffer");
        uart_driver_delete(port);
        return false;
    }

    char line_buf[MINMEA_MAX_SENTENCE_LENGTH];
    int  line_pos   = 0;
    bool has_rmc    = false;
    int  elapsed_ms = 0;

    memset(&out, 0, sizeof(out));

    while (elapsed_ms < timeout_ms && !has_rmc) {
        int len = uart_read_bytes(port, data, GPS_BUF_SIZE, pdMS_TO_TICKS(100));
        elapsed_ms += 100;

        for (int i = 0; i < len; i++) {
            char c = (char)data[i];

            if (c == '\n' || c == '\r') {
                line_buf[line_pos] = '\0';
                line_pos = 0;

                enum minmea_sentence_id id = minmea_sentence_id(line_buf, false);

                if (id == MINMEA_SENTENCE_RMC && !has_rmc) {
                    struct minmea_sentence_rmc frame;
                    if (minmea_parse_rmc(&frame, line_buf) &&
                        frame.valid && frame.date.year >= 20) {
                        out.latitude  = minmea_tocoord(&frame.latitude);
                        out.longitude = minmea_tocoord(&frame.longitude);
                        minmea_getdatetime(&out.time, &frame.date, &frame.time);
                        has_rmc = true;
                    }
                } else if (id == MINMEA_SENTENCE_GGA) {
                    struct minmea_sentence_gga frame;
                    if (minmea_parse_gga(&frame, line_buf) && frame.fix_quality > 0) {
                        out.altitude    = minmea_tofloat(&frame.altitude);
                        out.hasAltitude = true;
                    }
                }
            } else {
                if (line_pos < (int)sizeof(line_buf) - 1)
                    line_buf[line_pos++] = c;
            }
        }
    }

    free(data);
    uart_driver_delete(port);

    if (has_rmc) {
        ESP_LOGI(TAG, "Fix obtido: lat=%.6f lon=%.6f alt=%.1fm",
                 out.latitude, out.longitude, out.altitude);
    } else {
        ESP_LOGW(TAG, "waitForFix: timeout (%dms) sem fix válido", timeout_ms);
    }

    return has_rmc;
}

// ---- run (task contínua) -----------------------------------------------------

void GpsManager::run() {
    if (!_isInitialized) {
        ESP_LOGE(TAG, "Execute init() antes de iniciar a task");
        vTaskDelete(NULL);
        return;
    }

    uint8_t* data = (uint8_t*) malloc(GPS_BUF_SIZE);
    if (!data) {
        ESP_LOGE(TAG, "run: sem RAM para o buffer");
        vTaskDelete(NULL);
        return;
    }

    char line_buf[MINMEA_MAX_SENTENCE_LENGTH];
    int  line_pos = 0;

    ESP_LOGI(TAG, "Task GPS iniciada.");

    while (true) {
        int len = uart_read_bytes(_uart_port, data, GPS_BUF_SIZE, pdMS_TO_TICKS(100));

        for (int i = 0; i < len; i++) {
            char c = (char)data[i];

            if (c == '\n' || c == '\r') {
                line_buf[line_pos] = '\0';
                line_pos = 0;

                enum minmea_sentence_id id = minmea_sentence_id(line_buf, false);

                if (id == MINMEA_SENTENCE_RMC) {
                    struct minmea_sentence_rmc frame;
                    if (minmea_parse_rmc(&frame, line_buf) && frame.valid) {
                        _latitude  = minmea_tocoord(&frame.latitude);
                        _longitude = minmea_tocoord(&frame.longitude);
                        _hasFix    = true;
                    }
                } else if (id == MINMEA_SENTENCE_GGA) {
                    struct minmea_sentence_gga frame;
                    if (minmea_parse_gga(&frame, line_buf) && frame.fix_quality > 0) {
                        _altitude = minmea_tofloat(&frame.altitude);
                    }
                }
            } else {
                if (line_pos < (int)sizeof(line_buf) - 1)
                    line_buf[line_pos++] = c;
            }
        }
    }

    free(data);
}

void GpsManager::taskWrapper(void* _this) {
    static_cast<GpsManager*>(_this)->run();
}
