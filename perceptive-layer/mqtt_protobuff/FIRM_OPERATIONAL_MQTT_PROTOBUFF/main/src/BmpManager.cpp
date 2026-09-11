#include "BmpManager.h"
#include <stdio.h>
#include <string.h>
#include <driver/gpio.h>


#define BMP_I2C_SDA_PIN GPIO_NUM_8 
#define BMP_I2C_SCL_PIN GPIO_NUM_9

void BmpManager::init() {
    bmp280_params_t params;
    bmp280_init_default_params(&params);
    
    memset(&dev, 0, sizeof(bmp280_t));

    // Usando os pinos I2C
    ESP_ERROR_CHECK(bmp280_init_desc(&dev, BMP280_I2C_ADDRESS_0, I2C_NUM_0, BMP_I2C_SDA_PIN, BMP_I2C_SCL_PIN));
    
    ESP_ERROR_CHECK(bmp280_init(&dev, &params));

    bool bme280p = dev.id == BME280_CHIP_ID;
    printf("BMP280: found %s\n", bme280p ? "BME280" : "BMP280");
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