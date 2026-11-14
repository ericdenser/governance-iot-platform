#include "gov_hal.h"

#include "sdkconfig.h"
#include "esp_log.h"
#include "i2cdev.h"
#include "esp_adc/adc_oneshot.h"
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

static const char* TAG = "GovHal";

#define I2C_PROBE_HZ 100000

static bool                      s_i2c_ready = false;
static adc_oneshot_unit_handle_t s_adc       = nullptr;
static adc_cali_handle_t         s_cali      = nullptr;
static SemaphoreHandle_t         s_adc_mutex = nullptr;  // oneshot is not thread-safe

void gov_hal_init(void) {
    if (!s_i2c_ready) {
        i2cdev_init();
        s_i2c_ready = true;
    }
}

bool gov_i2c_probe(uint8_t addr) {
    gov_hal_init();

    i2c_dev_t dev = {};
    dev.port                 = GOV_I2C_PORT;
    dev.addr                 = addr;
    dev.cfg.sda_io_num       = (gpio_num_t)CONFIG_GOV_I2C_SDA_PIN;
    dev.cfg.scl_io_num       = (gpio_num_t)CONFIG_GOV_I2C_SCL_PIN;
    dev.cfg.sda_pullup_en    = true;
    dev.cfg.scl_pullup_en    = true;
    dev.cfg.master.clk_speed = I2C_PROBE_HZ;

    i2c_dev_create_mutex(&dev);
    bool present = (i2c_dev_probe(&dev, I2C_DEV_WRITE) == ESP_OK);
    i2c_dev_delete_mutex(&dev);
    return present;
}

static void adc_init(void) {
    if (s_adc != nullptr) return;
    s_adc_mutex = xSemaphoreCreateMutex();

    adc_oneshot_unit_init_cfg_t unit = {};
    unit.unit_id  = ADC_UNIT_1;
    unit.clk_src  = ADC_RTC_CLK_SRC_DEFAULT;
    unit.ulp_mode = ADC_ULP_MODE_DISABLE;
    ESP_ERROR_CHECK(adc_oneshot_new_unit(&unit, &s_adc));

    adc_cali_curve_fitting_config_t cali = {};
    cali.unit_id  = ADC_UNIT_1;
    cali.atten    = ADC_ATTEN_DB_12;
    cali.bitwidth = ADC_BITWIDTH_DEFAULT;
    if (adc_cali_create_scheme_curve_fitting(&cali, &s_cali) == ESP_OK) {
        ESP_LOGI(TAG, "Calibração ADC ativada");
    } else {
        s_cali = nullptr;
        ESP_LOGW(TAG, "Calibração ADC não disponível, usando fallback linear");
    }
}

// -1 if the GPIO is not on ADC1
static int adc1_channel(int gpio) {
    adc_unit_t unit;
    adc_channel_t channel;
    if (adc_oneshot_io_to_channel(gpio, &unit, &channel) != ESP_OK || unit != ADC_UNIT_1) return -1;
    return (int)channel;
}

bool gov_adc_config(int gpio) {
    adc_init();
    int channel = adc1_channel(gpio);
    if (channel < 0) {
        ESP_LOGE(TAG, "GPIO%d nao e um pino do ADC1", gpio);
        return false;
    }
    adc_oneshot_chan_cfg_t cfg = {};
    cfg.atten    = ADC_ATTEN_DB_12;
    cfg.bitwidth = ADC_BITWIDTH_DEFAULT;

    xSemaphoreTake(s_adc_mutex, portMAX_DELAY);
    esp_err_t err = adc_oneshot_config_channel(s_adc, (adc_channel_t)channel, &cfg);
    xSemaphoreGive(s_adc_mutex);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "adc_oneshot_config_channel GPIO%d falhou: %s", gpio, esp_err_to_name(err));
        return false;
    }
    return true;
}

int gov_adc_read_mv(int gpio, bool verbose) {
    adc_init();
    int channel = adc1_channel(gpio);
    if (channel < 0) return -1;

    xSemaphoreTake(s_adc_mutex, portMAX_DELAY);
    int sum = 0, valid = 0;
    for (int i = 0; i < 16; i++) {
        int val = 0;
        if (adc_oneshot_read(s_adc, (adc_channel_t)channel, &val) != ESP_OK) continue;
        sum += val;
        valid++;
    }
    xSemaphoreGive(s_adc_mutex);

    if (valid == 0) {
        ESP_LOGE(TAG, "Nenhuma amostra valida no GPIO%d", gpio);
        return -1;
    }
    int raw = sum / valid;
    int mv  = 0;
    if (s_cali) adc_cali_raw_to_voltage(s_cali, raw, &mv);
    else        mv = raw * 3100 / 4095;

    if (verbose) ESP_LOGI(TAG, "GPIO%d: raw=%d amostras_validas=%d/16 mv=%d", gpio, raw, valid, mv);
    return mv;
}
