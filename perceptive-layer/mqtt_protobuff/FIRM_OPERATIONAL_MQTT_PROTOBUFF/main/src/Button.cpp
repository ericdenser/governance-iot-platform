#include "Button.h"

#include "sdkconfig.h"
#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "lcd.h"

#define BUTTON_POLL_MS      10
#define BUTTON_DEBOUNCE_MS  30

static void button_task(void*) {
    const gpio_num_t pin = (gpio_num_t)CONFIG_GOV_UI_BUTTON_GPIO;
    bool stable_pressed = false;
    int  same_count     = 0;
    bool last_raw       = false;

    while (true) {
        bool raw = (gpio_get_level(pin) == 0);
        if (raw == last_raw) {
            if (same_count < BUTTON_DEBOUNCE_MS / BUTTON_POLL_MS) same_count++;
        } else {
            same_count = 0;
            last_raw   = raw;
        }

        if (same_count >= BUTTON_DEBOUNCE_MS / BUTTON_POLL_MS && raw != stable_pressed) {
            stable_pressed = raw;
            if (stable_pressed) display_next_screen();
        }
        vTaskDelay(pdMS_TO_TICKS(BUTTON_POLL_MS));
    }
}

void button_start() {
    gpio_config_t cfg = {};
    cfg.pin_bit_mask = 1ULL << CONFIG_GOV_UI_BUTTON_GPIO;
    cfg.mode         = GPIO_MODE_INPUT;
    cfg.pull_up_en   = GPIO_PULLUP_ENABLE;
    cfg.pull_down_en = GPIO_PULLDOWN_DISABLE;
    cfg.intr_type    = GPIO_INTR_DISABLE;
    gpio_config(&cfg);

    xTaskCreate(button_task, "ui_button", 3072, nullptr, 3, nullptr);
}
