#include <stdio.h>
#include "ui_common.h"
#include "LogCapture.h"

#define MAX_VISIBLE 30

static lv_obj_t* s_term     = nullptr;
static lv_obj_t* s_counter  = nullptr;
static uint32_t  s_last_seq = 0;

// Estatico pra nao pesar na stack da task do LVGL
static LogCapture::Line s_buf[MAX_VISIBLE];

static lv_color_t level_color(char level) {
    switch (level) {
        case 'E': return COLOR_ERR;
        case 'W': return COLOR_WARN;
        case 'I': return COLOR_OK;
        default:  return COLOR_MUTED;
    }
}

static void append(const LogCapture::Line& line) {
    lv_obj_t* l = ui_label(s_term, &lv_font_unscii_8, level_color(line.level), line.text);
    lv_obj_set_width(l, lv_pct(100));
    lv_label_set_long_mode(l, LV_LABEL_LONG_MODE_WRAP);

    while (lv_obj_get_child_count(s_term) > MAX_VISIBLE) {
        lv_obj_delete(lv_obj_get_child(s_term, 0));
    }
    s_last_seq = line.seq;
}

static void pull(bool scroll) {
    int n = LogCapture::read(s_last_seq, s_buf, MAX_VISIBLE);
    if (n == 0) return;
    for (int i = 0; i < n; i++) append(s_buf[i]);

    char txt[24];
    snprintf(txt, sizeof(txt), "#%lu", (unsigned long)s_last_seq);
    lv_label_set_text(s_counter, txt);

    if (scroll) {
        lv_obj_update_layout(s_term);
        lv_obj_scroll_to_y(s_term, LV_COORD_MAX, LV_ANIM_OFF);
    }
}

lv_obj_t* ui_log_create(int w, int h) {
    lv_obj_t* scr = ui_screen_create(SCREEN_LOG, false);

    lv_obj_t* title = ui_label(scr, &lv_font_montserrat_16, COLOR_TEXT, LV_SYMBOL_LIST "  LOG");
    lv_obj_align(title, LV_ALIGN_TOP_LEFT, 12, 10);

    s_counter = ui_label(scr, &lv_font_montserrat_12, COLOR_MUTED, UI_NO_DATA);
    lv_obj_align(s_counter, LV_ALIGN_TOP_RIGHT, -12, 13);

    s_term = lv_obj_create(scr);
    lv_obj_set_size(s_term, w - 12, h - 36 - 16);
    lv_obj_align(s_term, LV_ALIGN_TOP_MID, 0, 36);
    lv_obj_set_style_bg_color(s_term, COLOR_TERMINAL, 0);
    lv_obj_set_style_bg_opa(s_term, LV_OPA_COVER, 0);
    lv_obj_set_style_border_color(s_term, COLOR_CARD, 0);
    lv_obj_set_style_border_width(s_term, 1, 0);
    lv_obj_set_style_radius(s_term, 6, 0);
    lv_obj_set_style_pad_all(s_term, 4, 0);
    lv_obj_set_style_pad_row(s_term, 3, 0);
    lv_obj_set_flex_flow(s_term, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_scrollbar_mode(s_term, LV_SCROLLBAR_MODE_OFF);

    return scr;
}

void ui_log_on_show() {
    lv_obj_clean(s_term);
    s_last_seq = 0;
    pull(true);
}

void ui_log_tick() {
    pull(true);
}
