#pragma once

#include <time.h>
#include "lvgl.h"

#define COLOR_BG        lv_color_hex(0x0F1115)
#define COLOR_CARD      lv_color_hex(0x1A1D23)
#define COLOR_TEXT      lv_color_hex(0xE6E8EB)
#define COLOR_MUTED     lv_color_hex(0x6B7280)
#define COLOR_ACCENT    lv_color_hex(0x3B82F6)
#define COLOR_OK        lv_color_hex(0x22C55E)
#define COLOR_WARN      lv_color_hex(0xF59E0B)
#define COLOR_ERR       lv_color_hex(0xEF4444)
#define COLOR_ERR_CARD  lv_color_hex(0x3A1518)
#define COLOR_WARN_CARD lv_color_hex(0x3A2A10)
#define COLOR_TEMP      lv_color_hex(0xF97316)
#define COLOR_HUM       lv_color_hex(0x06B6D4)
#define COLOR_PRESS     lv_color_hex(0x8B5CF6)
#define COLOR_TERMINAL  lv_color_hex(0x05070A)

#define UI_NO_DATA "-"

enum ScreenId {
    SCREEN_BOOT = 0,
    SCREEN_LOG,
    SCREEN_DASHBOARD,
    SCREEN_STATUS,
    SCREEN_COUNT
};

lv_obj_t* ui_label(lv_obj_t* parent, const lv_font_t* font, lv_color_t color, const char* text);

// set_text reinicia a rolagem de labels SCROLL_CIRCULAR
void ui_label_update(lv_obj_t* label, const char* text);

// false = relogio nao sincronizado
bool ui_local_time(struct tm* out);

lv_obj_t* ui_screen_create(ScreenId id, bool use_active);

lv_obj_t* ui_card(lv_obj_t* parent, int w, int h);

// Chamadas sempre com o lock do LVGL
lv_obj_t* ui_boot_create(int w, int h);
bool      ui_boot_on_state(const char* state, const char* detail, bool is_error);  // true = OPERATIONAL

lv_obj_t* ui_log_create(int w, int h);
void      ui_log_on_show();
void      ui_log_tick();

lv_obj_t* ui_dashboard_create(int w, int h);
void      ui_dashboard_tick();
void      ui_dashboard_sample();

lv_obj_t* ui_status_create(int w, int h);
void      ui_status_tick();
void      ui_status_on_state(const char* state, const char* detail, bool is_error);
void      ui_status_set_sensors(const char* csv);
