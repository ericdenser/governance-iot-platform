// =============================================================================
//  Firmware operacional — user code
//
//  Este main é o "molde" pra outros firmwares baseados no governance_core.
//  Implementa os hooks de:
//    - sensor discovery / read telemetry (obrigatórios pra publicar dados)
//    - get_persisted_time / persist_time (opcional, DS3231)
//    - get_external_time (opcional, GPS)
//    - get_battery_mv (opcional, bateria)
//  Toda a state machine, NVS, WiFi, MQTT, OTA, boot audit, error handling
//  moram no componente governance_core.
// =============================================================================

#include <string>
#include <string.h>
#include <time.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "esp_sleep.h"
#include "ds3231.h"
#include "i2cdev.h"
#include "esp_wifi.h"
#include "driver/temperature_sensor.h"
#include "governance_core.h"

// Drivers de sensor específicos deste hardware
#include "src/SensorDiscovery.h"
#include "src/GpsManager.h"
#include "src/BatteryManager.h"
#include "src/BmpManager.h"
#include "src/AdcManager.h"
#include "src/SensorCache.h"
#include "src/ChipTempManager.h"
#include "src/Mq135Manager.h"

#if CONFIG_GOV_LCD_ENABLED
#include "src/lcd.h"
#include "src/LogCapture.h"
#include "src/Button.h"
#endif

// =============================================================================
//  Estado do hardware — mapa de sensores + managers específicos
// =============================================================================
static SensorMap   g_sensors      = {};
static GpsManager  g_gpsManager;
static BmpManager  g_bmpManager;
static std::string g_activeSensorsCSV;

// =============================================================================
//  DS3231 helpers (RTC persistente)
// =============================================================================
// Config do bus I2C onde o DS3231 vive
#define RTC_I2C_PORT     I2C_NUM_0
#define RTC_I2C_SDA_PIN  ((gpio_num_t)CONFIG_GOV_I2C_SDA_PIN)
#define RTC_I2C_SCL_PIN  ((gpio_num_t)CONFIG_GOV_I2C_SCL_PIN)
#define RTC_I2C_FREQ     400000

static bool probeDS3231(void) {
    i2c_dev_t probe = {};
    probe.port                 = RTC_I2C_PORT;
    probe.addr                 = 0x68;
    probe.cfg.sda_io_num       = RTC_I2C_SDA_PIN;
    probe.cfg.scl_io_num       = RTC_I2C_SCL_PIN;
    probe.cfg.sda_pullup_en    = true;
    probe.cfg.scl_pullup_en    = true;
    probe.cfg.master.clk_speed = RTC_I2C_FREQ;
    i2c_dev_create_mutex(&probe);
    bool present = (i2c_dev_probe(&probe, I2C_DEV_WRITE) == ESP_OK);
    i2c_dev_delete_mutex(&probe);
    return present;
}

// =============================================================================
//  Hooks do governance_core
// =============================================================================

// ---- Sensor discovery + telemetry ------------------------------------------
static const char* my_sensor_discovery(void) {
    // Liga GPS pra probe UART funcionar
    gpio_set_level((gpio_num_t)CONFIG_GOV_GPS_POWER_PIN, 1);
    vTaskDelay(pdMS_TO_TICKS(500));

    SensorDiscovery::run(g_sensors, &g_gpsManager);

    g_activeSensorsCSV.clear();
    auto appendSensor = [](const char* name) {
        if (!g_activeSensorsCSV.empty()) g_activeSensorsCSV += ',';
        g_activeSensorsCSV += name;
    };
    if (g_sensors.bmp280)      appendSensor("bmp280");
    if (g_sensors.ds3231)      appendSensor("ds3231");
    if (g_sensors.gps)         appendSensor("gps");
    if (g_sensors.battery_adc) appendSensor("battery_adc");

    float mq_ppm;
    if (Mq135Manager::read(mq_ppm)) appendSensor("mq135");

    if (g_sensors.bmp280) {
        g_bmpManager.init(g_sensors.bmp280_addr);
    }

    SensorCache::start(g_sensors.bmp280 ? &g_bmpManager : nullptr);
#if CONFIG_GOV_LCD_ENABLED
    display_set_sensors(g_activeSensorsCSV.c_str());
#endif

    // Se GPS detectado, cria task dedicada de leitura contínua
    if (g_sensors.gps) {
        xTaskCreate(GpsManager::taskWrapper, "gps", 4096, &g_gpsManager, 3, NULL);
    } else {
        gpio_set_level((gpio_num_t)CONFIG_GOV_GPS_POWER_PIN, 0);
    }

    return g_activeSensorsCSV.c_str();
}

static int my_read_telemetry(sensor_reading_t* out, int max) {
    int n = 0;

    if (g_sensors.gps && g_gpsManager.hasFix() && n + 3 <= max) {
        strncpy(out[n].key, "latitude",  sizeof(out[n].key) - 1);
        out[n].value = g_gpsManager.getLat(); n++;
        strncpy(out[n].key, "longitude", sizeof(out[n].key) - 1);
        out[n].value = g_gpsManager.getLon(); n++;
        strncpy(out[n].key, "altitude",  sizeof(out[n].key) - 1);
        out[n].value = g_gpsManager.getAlt(); n++;
    }

    if (g_sensors.battery_adc && n < max) {

        float bat = BatteryManager::readBattery();
        SensorCache::setBattery(bat);
        if (bat > 0.0f) {
            strncpy(out[n].key, "battery_mv", sizeof(out[n].key) - 1);
            out[n].value = bat; n++;
        }
    }

    SensorSnapshot snap;
    bool fresh = SensorCache::getFresh(snap, 3 * SensorCache::SAMPLE_PERIOD_MS);

    if (fresh && snap.env_valid && n < max) {
        strncpy(out[n].key, "temperature", sizeof(out[n].key) - 1);
        out[n].value = snap.temp_c;
        n++;
    }

    if (fresh && snap.hum_valid && n < max) {
        strncpy(out[n].key, "humidity", sizeof(out[n].key) - 1);
        out[n].value = snap.hum_pct;
        n++;
    }

    // get RSSI
    wifi_ap_record_t ap_info;
    if (esp_wifi_sta_get_ap_info(&ap_info) == ESP_OK && n < max) {
        strncpy(out[n].key, "rssi", sizeof(out[n].key) -1 );
        out[n].value = (float)ap_info.rssi;
        n++;
    }

    // Internal temp of SoC
    if (fresh && snap.chip_valid && n < max) {
        strncpy(out[n].key, "temp_c", sizeof(out[n].key) - 1);
        out[n].value = snap.chip_c;
        n++;
    }

    return n;
}

// ---- Time sync hooks: DS3231 (persisted) + GPS (external) ------------------

static time_t my_get_persisted_time(void) {
    if (!probeDS3231()) return 0;

    i2c_dev_t rtc = {};
    if (ds3231_init_desc(&rtc, RTC_I2C_PORT, RTC_I2C_SDA_PIN, RTC_I2C_SCL_PIN) != ESP_OK) return 0;
    rtc.cfg.sda_pullup_en    = true;
    rtc.cfg.scl_pullup_en    = true;
    rtc.cfg.master.clk_speed = RTC_I2C_FREQ;

    struct tm ti = {};
    time_t result = 0;
    if (ds3231_get_time(&rtc, &ti) == ESP_OK && ti.tm_year >= 124) {
        ti.tm_isdst = 0;
        result = mktime(&ti);
    }
    ds3231_free_desc(&rtc);
    return result;
}

static void my_persist_time(time_t epoch) {
    if (!probeDS3231()) return;

    i2c_dev_t rtc = {};
    if (ds3231_init_desc(&rtc, RTC_I2C_PORT, RTC_I2C_SDA_PIN, RTC_I2C_SCL_PIN) != ESP_OK) return;
    rtc.cfg.sda_pullup_en    = true;
    rtc.cfg.scl_pullup_en    = true;
    rtc.cfg.master.clk_speed = RTC_I2C_FREQ;

    struct tm ti;
    gmtime_r(&epoch, &ti);
    ds3231_set_time(&rtc, &ti);
    ds3231_free_desc(&rtc);
}

static time_t my_get_external_time(uint32_t timeout_ms) {
    // Liga GPS
    gpio_set_level((gpio_num_t)CONFIG_GOV_GPS_POWER_PIN, 1);
    vTaskDelay(pdMS_TO_TICKS(500));

    GpsFix fix = {};
    time_t result = 0;
    if (GpsManager::waitForFix((gpio_num_t)CONFIG_GOV_GPS_UART_TX_PIN,
                               (gpio_num_t)CONFIG_GOV_GPS_UART_RX_PIN,
                               (uart_port_t)CONFIG_GOV_GPS_UART_PORT,
                               fix, timeout_ms)) {
        struct tm ti = fix.time;
        ti.tm_isdst = 0;
        result = mktime(&ti);
    }

    // Desliga GPS depois do sync (economia). Se sensor_discovery detectar
    // depois, liga de novo.
    gpio_set_level((gpio_num_t)CONFIG_GOV_GPS_POWER_PIN, 0);
    return result;
}

// Hook consumido pelo governance_core (streak no telemetry_task + wake check
// pos deep_sleep). Contrato: retorna 0 quando leitura invalida, nesse caso o
// core NAO conta pro streak e nao considera pra decisao de hibernar.
static uint32_t my_get_battery_mv(void) {
    float mv = BatteryManager::readBattery();
    SensorCache::setBattery(mv);
    return (mv > 0.0f) ? (uint32_t)mv : 0u;
}

#if CONFIG_GOV_LCD_ENABLED
static void my_display_lcd(int width, int height) {
    display_init(width, height);
    button_start();
}
#endif

// =============================================================================
//  app_main
// =============================================================================
extern "C" void app_main(void) {
#if CONFIG_GOV_LCD_ENABLED
    LogCapture::install();
#endif

    // Init do bus I2C (DS3231 usa)

    i2cdev_init();
    AdcManager::init();
    SensorCache::init();
    ChipTempManager::init();


    // GPIO de controle do transistor PNP que liga/desliga VCC do GPS
    gpio_reset_pin((gpio_num_t)CONFIG_GOV_GPS_POWER_PIN);
    gpio_set_direction((gpio_num_t)CONFIG_GOV_GPS_POWER_PIN, GPIO_MODE_OUTPUT);
    gpio_set_level((gpio_num_t)CONFIG_GOV_GPS_POWER_PIN, 0); // GPS começa desligado

    // Monta hooks e delega pro core
    governance_hooks_t hooks = {};
    hooks.sensor_discovery    = my_sensor_discovery;
    hooks.read_telemetry      = my_read_telemetry;
    hooks.get_persisted_time  = my_get_persisted_time;
    hooks.persist_time        = my_persist_time;
    hooks.get_external_time   = my_get_external_time;
    hooks.get_battery_mv   = my_get_battery_mv;  
#if CONFIG_GOV_LCD_ENABLED
    hooks.display_lcd         = my_display_lcd;
    hooks.on_state_change     = display_show_state;
#endif

    governance_core_init(&hooks);
}
