#pragma once

#include "GpsManager.h"

// ---- Pinos de hardware -------------------------------------------------------
#define I2C_PORT         I2C_NUM_0
#define I2C_SDA_PIN      GPIO_NUM_8
#define I2C_SCL_PIN      GPIO_NUM_9
#define I2C_FREQ_HZ      100000

// GPS: vem do Kconfig do governance_core (menu Governance IoT)
#define GPS_UART_PORT    ((uart_port_t)CONFIG_GOV_GPS_UART_PORT)
#define GPS_UART_RX_PIN  ((gpio_num_t)CONFIG_GOV_GPS_UART_RX_PIN)  // NEO TX → ESP RX
#define GPS_UART_TX_PIN  ((gpio_num_t)CONFIG_GOV_GPS_UART_TX_PIN)  // NEO RX → ESP TX
#define GPS_POWER_PIN    ((gpio_num_t)CONFIG_GOV_GPS_POWER_PIN)    // NPN base → corta VCC do GPS

// ---- Endereços I2C conhecidos ------------------------------------------------
#define I2C_ADDR_BMP280_A  0x76  // SDO = GND
#define I2C_ADDR_BMP280_B  0x77  // SDO = VCC
#define I2C_ADDR_DS3231    0x68

struct SensorMap {
    bool bmp280;
    bool ds3231;
    bool gps;
    bool battery_adc;
};

class SensorDiscovery {
public:
    static void run(SensorMap& map, GpsManager* gps);

private:
    static bool probeI2CAddr(uint8_t addr);
    static bool probeGpsUart();
};
