#include "BmpManager.h"
#include <stdio.h>
#include <string.h>
#include <driver/gpio.h>
#include "sdkconfig.h"


#define BMP_I2C_SDA_PIN ((gpio_num_t)CONFIG_GOV_I2C_SDA_PIN)
#define BMP_I2C_SCL_PIN ((gpio_num_t)CONFIG_GOV_I2C_SCL_PIN)

void BmpManager::init(uint8_t addr) {
    bmp280_params_t params;
    bmp280_init_default_params(&params);
    
    memset(&dev, 0, sizeof(bmp280_t));

    // Usando os pinos I2C
    ESP_ERROR_CHECK(bmp280_init_desc(&dev, addr, I2C_NUM_0, BMP_I2C_SDA_PIN, BMP_I2C_SCL_PIN));
    
    ESP_ERROR_CHECK(bmp280_init(&dev, &params));

    bool bme280p = dev.id == BME280_CHIP_ID;
    printf("BMP280: found %s at 0x%02X\n", bme280p ? "BME280" : "BMP280", addr);
}

bool BmpManager::readTemp(float &temp) {
    float dummy_press;
    if(bmp280_read_float(&dev, &temp, &dummy_press, nullptr) == ESP_OK) {
        return true;
    }
    return false;
}

bool BmpManager::readPressure(float &pressure) {
    float dummy_temp;
    if(bmp280_read_float(&dev, &dummy_temp, &pressure, nullptr) == ESP_OK) {
        return true;
    }
    return false;
}

bool BmpManager::readHumidity(float &humidity) {
    float dummy_temp, dummy_press;
    if(bmp280_read_float(&dev, &dummy_temp, &dummy_press, &humidity) == ESP_OK) {
        return true;
    }
    return false;
}

bool BmpManager::readAll(float &temp, float &pressure, float &humidity) {
    humidity = 0.0f;
    return bmp280_read_float(&dev, &temp, &pressure, isBme() ? &humidity : nullptr) == ESP_OK;
}
