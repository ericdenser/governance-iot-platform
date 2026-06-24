#include "SensorDiscovery.h"
#include "AdcManager.h"
#include "esp_log.h"
#include "i2cdev.h"
#include "driver/uart.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char* TAG = "SensorDiscovery";

// ---- I2C probe via i2cdev ------

bool SensorDiscovery::probeI2CAddr(uint8_t addr) {
    i2c_dev_t dev = {};
    dev.port               = I2C_PORT;
    dev.addr               = addr;
    dev.cfg.sda_io_num     = I2C_SDA_PIN;
    dev.cfg.scl_io_num     = I2C_SCL_PIN;
    dev.cfg.sda_pullup_en  = true;
    dev.cfg.scl_pullup_en  = true;
    dev.cfg.master.clk_speed = I2C_FREQ_HZ;

    i2c_dev_create_mutex(&dev);
    bool present = (i2c_dev_probe(&dev, I2C_DEV_WRITE) == ESP_OK);
    i2c_dev_delete_mutex(&dev);
    return present;
}

// ---- GPS UART probe ----------------------------------------------------------

bool SensorDiscovery::probeGpsUart() {
    uart_config_t cfg = {
        .baud_rate  = 9600,
        .data_bits  = UART_DATA_8_BITS,
        .parity     = UART_PARITY_DISABLE,
        .stop_bits  = UART_STOP_BITS_1,
        .flow_ctrl  = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };

    if (uart_driver_install(GPS_UART_PORT, 256, 0, 0, NULL, 0) != ESP_OK) {
        ESP_LOGE(TAG, "Falha ao instalar driver UART para probe GPS");
        return false;
    }
    uart_param_config(GPS_UART_PORT, &cfg);
    uart_set_pin(GPS_UART_PORT, GPS_UART_TX_PIN, GPS_UART_RX_PIN,
                 UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE);

    uint8_t buf[64];
    bool found = false;

    for (int i = 0; i < 20 && !found; i++) {
        int len = uart_read_bytes(GPS_UART_PORT, buf, sizeof(buf), pdMS_TO_TICKS(100));
        for (int j = 0; j < len; j++) {
            if (buf[j] == '$') { found = true; break; }
        }
    }

    uart_driver_delete(GPS_UART_PORT);
    return found;
}

// ---- run --------------------------------------------------------------------

void SensorDiscovery::run(SensorMap& map, GpsManager* gps) {
    map = {};

    // --- I2C scan (usa i2c_dev_probe — sem retry, retorno imediato) -----------
    ESP_LOGI(TAG, "Iniciando varredura I2C (0x08..0x77)...");
    bool anyI2C = false;

    for (uint8_t addr = 0x08; addr < 0x78; addr++) {
        if (!probeI2CAddr(addr)) continue;

        anyI2C = true;
        ESP_LOGI(TAG, "  Dispositivo encontrado em 0x%02X", addr);

        if (addr == I2C_ADDR_BMP280_A || addr == I2C_ADDR_BMP280_B) {
            map.bmp280 = true;
            ESP_LOGI(TAG, "  → BMP280 detectado (0x%02X)", addr);
        }
        if (addr == I2C_ADDR_DS3231) {
            map.ds3231 = true;
            ESP_LOGI(TAG, "  → DS3231 detectado (0x%02X)", addr);
        }
    }

    if (!anyI2C) {
        ESP_LOGW(TAG, "Nenhum dispositivo I2C detectado no barramento.");
    }

    // --- GPS UART -------------------------------------------------------------
    ESP_LOGI(TAG, "Verificando presença do GPS (RX:%d TX:%d)...", GPS_UART_RX_PIN, GPS_UART_TX_PIN);
    if (probeGpsUart()) {
        map.gps = true;
        ESP_LOGI(TAG, "  → GPS detectado. Inicializando driver...");
        if (gps) {
            gps->init(GPS_UART_TX_PIN, GPS_UART_RX_PIN, GPS_UART_PORT);
        }
    } else {
        ESP_LOGW(TAG, "  GPS não detectado na UART%d", GPS_UART_PORT);
    }

    // --- ADC bateria ----------------------------------------------------------
    AdcManager::init();
    AdcManager::configChannel(ADC_CHANNEL_3);
    int mv = AdcManager::readMilliVolts(ADC_CHANNEL_3);
    if (mv > 500 && mv < 3200) {
        map.battery_adc = true;
        ESP_LOGI(TAG, "  → ADC bateria detectado (%d mV no pino, divisor conectado)", mv);
    } else {
        ESP_LOGW(TAG, "  ADC bateria fora do range esperado (%d mV) — divisor de tensão ausente?", mv);
    }
}
