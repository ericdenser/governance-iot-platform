// =============================================================================
//  Firmware operacional — user code
//
//  O governance_core (kernel) cuida da state machine, NVS, WiFi, MQTT, OTA,
//  boot audit, erros e telemetria. Este main so registra os plugins de sensor
//  habilitados no menuconfig e liga os hooks opcionais (hora, bateria, display).
//  Sensor novo: gov_plugins/README.md
// =============================================================================

#include <time.h>
#include "sdkconfig.h"
#include "governance_core.h"
#include "gov_sensor.h"
#include "gov_sensor_bmp280.h"
#include "gov_sensor_mq135.h"
#include "gov_sensor_gps.h"
#include "gov_sensor_ds3231.h"
#include "gov_sensor_battery.h"
#include "gov_sensor_chip_temp.h"
#include "gov_sensor_flow.h"
#include "gov_sensor_aht25.h"
#include "gov_sensor_env.h"

#if CONFIG_GOV_LCD_ENABLED
#include "src/lcd.h"
#include "src/LogCapture.h"
#include "src/Button.h"
#endif

// With the fusion on, BMP280 and AHT25 only feed the cache and env sends the combined values
#if CONFIG_GOV_SENSOR_ENV
#define ENV_SOURCE_FLAGS GOV_SENSOR_NO_TELEMETRY
#else
#define ENV_SOURCE_FLAGS 0
#endif

static void register_sensors(void) {
#if CONFIG_GOV_SENSOR_BMP280
    gov_sensor_register_flags(&gov_sensor_bmp280, ENV_SOURCE_FLAGS);
#endif
#if CONFIG_GOV_SENSOR_AHT25
    gov_sensor_register_flags(&gov_sensor_aht25, ENV_SOURCE_FLAGS);
#endif
#if CONFIG_GOV_MQ135_ENABLED
    gov_sensor_register(&gov_sensor_mq135);
#endif
#if CONFIG_GOV_SENSOR_GPS
    gov_sensor_register(&gov_sensor_gps);
#endif
#if CONFIG_GOV_SENSOR_DS3231
    gov_sensor_register(&gov_sensor_ds3231);
#endif
#if CONFIG_GOV_SENSOR_BATTERY
    gov_sensor_register(&gov_sensor_battery);
#endif
#if CONFIG_GOV_SENSOR_CHIP_TEMP
    gov_sensor_register(&gov_sensor_chip_temp);
#endif
#if CONFIG_GOV_SENSOR_FLOW
    gov_sensor_register(&gov_sensor_flow);
#endif
#if CONFIG_GOV_SENSOR_ENV
    gov_sensor_register_flags(&gov_sensor_env, GOV_SENSOR_DERIVED);   // after bmp280 and aht25
#endif
}

#if CONFIG_GOV_SENSOR_BATTERY
// 0 = invalid reading (the core ignores it for the low battery streak)
static uint32_t my_get_battery_mv(void) {
    float mv = gov_battery_read_mv();
    return mv > 0.0f ? (uint32_t)mv : 0u;
}
#endif

#if CONFIG_GOV_LCD_ENABLED
static void my_display_lcd(int width, int height) {
    display_init(width, height);
    button_start();
}
#endif

extern "C" void app_main(void) {
#if CONFIG_GOV_LCD_ENABLED
    LogCapture::install();
#endif
#if CONFIG_GOV_SENSOR_GPS
    gov_gps_power(false);
#endif

    register_sensors();

    governance_hooks_t hooks = {};
#if CONFIG_GOV_SENSOR_DS3231
    hooks.get_persisted_time = gov_ds3231_read_time;
    hooks.persist_time       = gov_ds3231_write_time;
#endif
#if CONFIG_GOV_SENSOR_GPS
    hooks.get_external_time  = gov_gps_wait_time;
#endif
#if CONFIG_GOV_SENSOR_BATTERY
    hooks.get_battery_mv     = my_get_battery_mv;
#endif
#if CONFIG_GOV_LCD_ENABLED
    hooks.display_lcd        = my_display_lcd;
    hooks.on_state_change    = display_show_state;
#endif

    governance_core_init(&hooks);
}
