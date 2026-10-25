#include "lcd.h"

#include "sdkconfig.h"
#include "esp_log.h"
#include "driver/gpio.h"
#include "driver/spi_master.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_vendor.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lvgl_port.h"
#include "ui_common.h"

static const char* TAG = "lcd";

#define LCD_HOST        SPI2_HOST
#define LCD_PCLK_HZ     (40 * 1000 * 1000)
#define LCD_DRAW_LINES  40

#define UI_TICK_MS        1000
#define UI_SAMPLE_EVERY   10    // ticks entre pontos dos graficos
#define UI_LOG_TICK_MS    250
#define UI_ANIM_MS        250

static bool       s_ready          = false;
static lv_obj_t*  s_screens[SCREEN_COUNT];
static int        s_current        = SCREEN_BOOT;
static bool       s_user_navigated = false;
static bool       s_auto_scheduled = false;

static void go_to(int id) {
    if (id == s_current) return;
    s_current = id;

    switch (id) {
        case SCREEN_LOG:       ui_log_on_show();     break;
        case SCREEN_DASHBOARD: ui_dashboard_tick();  break;
        case SCREEN_STATUS:    ui_status_tick();     break;
        default: break;
    }
    lv_screen_load_anim(s_screens[id], LV_SCREEN_LOAD_ANIM_MOVE_LEFT, UI_ANIM_MS, 0, false);
}

static void ui_tick_cb(lv_timer_t*) {
    static int n = 0;
    if (++n >= UI_SAMPLE_EVERY) {
        n = 0;
        ui_dashboard_sample();
    }
    if (s_current == SCREEN_DASHBOARD) ui_dashboard_tick();
    if (s_current == SCREEN_STATUS)    ui_status_tick();
}

static void log_tick_cb(lv_timer_t*) {
    if (s_current == SCREEN_LOG) ui_log_tick();
}

static void auto_dashboard_cb(lv_timer_t*) {
    if (!s_user_navigated && s_current == SCREEN_BOOT) go_to(SCREEN_DASHBOARD);
}

#define LCD_CHECK(expr, what)                                              \
    do {                                                                   \
        esp_err_t _e = (expr);                                             \
        if (_e != ESP_OK) {                                                \
            ESP_LOGE(TAG, "%s falhou: %s", what, esp_err_to_name(_e));     \
            return;                                                        \
        }                                                                  \
    } while (0)

void display_init(int width, int height) {
#if CONFIG_GOV_LCD_PIN_BL >= 0
    gpio_reset_pin((gpio_num_t)CONFIG_GOV_LCD_PIN_BL);
    gpio_set_direction((gpio_num_t)CONFIG_GOV_LCD_PIN_BL, GPIO_MODE_OUTPUT);
    gpio_set_level((gpio_num_t)CONFIG_GOV_LCD_PIN_BL, 0);
#endif

    spi_bus_config_t buscfg = {};
    buscfg.mosi_io_num     = CONFIG_GOV_LCD_PIN_MOSI;
    buscfg.miso_io_num     = -1;
    buscfg.sclk_io_num     = CONFIG_GOV_LCD_PIN_SCLK;
    buscfg.quadwp_io_num   = -1;
    buscfg.quadhd_io_num   = -1;
    buscfg.max_transfer_sz = width * LCD_DRAW_LINES * sizeof(uint16_t);
    LCD_CHECK(spi_bus_initialize(LCD_HOST, &buscfg, SPI_DMA_CH_AUTO), "spi_bus_initialize");

    esp_lcd_panel_io_handle_t io_handle = nullptr;
    esp_lcd_panel_io_spi_config_t io_config = {};
    io_config.cs_gpio_num       = CONFIG_GOV_LCD_PIN_CS;
    io_config.dc_gpio_num       = CONFIG_GOV_LCD_PIN_DC;
    io_config.spi_mode          = 0;
    io_config.pclk_hz           = LCD_PCLK_HZ;
    io_config.trans_queue_depth = 10;
    io_config.lcd_cmd_bits      = 8;
    io_config.lcd_param_bits    = 8;
    LCD_CHECK(esp_lcd_new_panel_io_spi((esp_lcd_spi_bus_handle_t)LCD_HOST, &io_config, &io_handle),
              "esp_lcd_new_panel_io_spi");

    esp_lcd_panel_handle_t panel_handle = nullptr;
    esp_lcd_panel_dev_config_t panel_config = {};
    panel_config.reset_gpio_num = CONFIG_GOV_LCD_PIN_RST;
    panel_config.rgb_ele_order  = LCD_RGB_ELEMENT_ORDER_RGB;
    panel_config.bits_per_pixel = 16;
    LCD_CHECK(esp_lcd_new_panel_st7789(io_handle, &panel_config, &panel_handle), "esp_lcd_new_panel_st7789");

    LCD_CHECK(esp_lcd_panel_reset(panel_handle), "esp_lcd_panel_reset");
    LCD_CHECK(esp_lcd_panel_init(panel_handle), "esp_lcd_panel_init");
#if CONFIG_GOV_LCD_INVERT_COLOR
    esp_lcd_panel_invert_color(panel_handle, true);
#endif
    esp_lcd_panel_disp_on_off(panel_handle, true);

    const lvgl_port_cfg_t lvgl_cfg = ESP_LVGL_PORT_INIT_CONFIG();
    LCD_CHECK(lvgl_port_init(&lvgl_cfg), "lvgl_port_init");

    lvgl_port_display_cfg_t disp_cfg = {};
    disp_cfg.io_handle        = io_handle;
    disp_cfg.panel_handle     = panel_handle;
    disp_cfg.buffer_size      = width * LCD_DRAW_LINES;
    disp_cfg.double_buffer    = true;
    disp_cfg.hres             = width;
    disp_cfg.vres             = height;
    disp_cfg.monochrome       = false;
    disp_cfg.color_format     = LV_COLOR_FORMAT_RGB565;
    disp_cfg.flags.buff_dma   = 1;
    disp_cfg.flags.swap_bytes = 1;
    if (lvgl_port_add_disp(&disp_cfg) == nullptr) {
        ESP_LOGE(TAG, "lvgl_port_add_disp falhou");
        return;
    }

    lvgl_port_lock(0);
    s_screens[SCREEN_BOOT]      = ui_boot_create(width, height);
    s_screens[SCREEN_LOG]       = ui_log_create(width, height);
    s_screens[SCREEN_DASHBOARD] = ui_dashboard_create(width, height);
    s_screens[SCREEN_STATUS]    = ui_status_create(width, height);
    lv_timer_create(ui_tick_cb, UI_TICK_MS, nullptr);
    lv_timer_create(log_tick_cb, UI_LOG_TICK_MS, nullptr);
    s_ready = true;
    lvgl_port_unlock();

#if CONFIG_GOV_LCD_PIN_BL >= 0
    gpio_set_level((gpio_num_t)CONFIG_GOV_LCD_PIN_BL, 1);
#endif
    ESP_LOGI(TAG, "Display %dx%d pronto", width, height);
}

void display_show_state(const char* state, const char* detail, bool is_error) {
    if (!s_ready || state == nullptr) return;
    // Chamado pela maquina de estados: nao pode ficar preso esperando a tela
    if (!lvgl_port_lock(100)) return;

    bool online = ui_boot_on_state(state, detail, is_error);
    ui_status_on_state(state, detail, is_error);

    if (online && !s_auto_scheduled) {
        s_auto_scheduled = true;
        lv_timer_t* t = lv_timer_create(auto_dashboard_cb, CONFIG_GOV_UI_AUTO_DASHBOARD_MS, nullptr);
        lv_timer_set_repeat_count(t, 1);
    }
    lvgl_port_unlock();
}

void display_next_screen() {
    if (!s_ready) return;
    if (!lvgl_port_lock(100)) return;
    s_user_navigated = true;
    go_to((s_current + 1) % SCREEN_COUNT);
    lvgl_port_unlock();
}

void display_set_sensors(const char* csv) {
    if (!s_ready) return;
    if (!lvgl_port_lock(100)) return;
    ui_status_set_sensors(csv);
    lvgl_port_unlock();
}
