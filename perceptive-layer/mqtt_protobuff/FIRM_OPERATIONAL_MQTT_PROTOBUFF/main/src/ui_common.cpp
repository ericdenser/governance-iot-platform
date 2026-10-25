#include <string.h>
#include "sdkconfig.h"
#include "ui_common.h"

bool ui_local_time(struct tm* out) {
    time_t now = time(nullptr);
    if (now < 1700000000) return false;
    now += (time_t)CONFIG_GOV_UI_UTC_OFFSET_MIN * 60;
    gmtime_r(&now, out);
    return true;
}

lv_obj_t* ui_label(lv_obj_t* parent, const lv_font_t* font, lv_color_t color, const char* text) {
    lv_obj_t* l = lv_label_create(parent);
    lv_obj_set_style_text_font(l, font, 0);
    lv_obj_set_style_text_color(l, color, 0);
    lv_label_set_text(l, text);
    return l;
}

void ui_label_update(lv_obj_t* label, const char* text) {
    const char* cur = lv_label_get_text(label);
    if (cur && strcmp(cur, text) == 0) return;
    lv_label_set_text(label, text);
}

lv_obj_t* ui_screen_create(ScreenId id, bool use_active) {
    lv_obj_t* scr = use_active ? lv_screen_active() : lv_obj_create(NULL);
    lv_obj_set_style_bg_color(scr, COLOR_BG, 0);
    lv_obj_set_style_bg_opa(scr, LV_OPA_COVER, 0);
    lv_obj_remove_flag(scr, LV_OBJ_FLAG_SCROLLABLE);

    lv_obj_t* dots = lv_obj_create(scr);
    lv_obj_remove_style_all(dots);
    lv_obj_set_size(dots, LV_SIZE_CONTENT, 6);
    lv_obj_set_flex_flow(dots, LV_FLEX_FLOW_ROW);
    lv_obj_set_style_pad_column(dots, 5, 0);
    lv_obj_align(dots, LV_ALIGN_BOTTOM_MID, 0, -4);
    for (int i = 0; i < SCREEN_COUNT; i++) {
        lv_obj_t* d = lv_obj_create(dots);
        lv_obj_remove_style_all(d);
        lv_obj_set_size(d, i == id ? 14 : 6, 6);
        lv_obj_set_style_radius(d, 3, 0);
        lv_obj_set_style_bg_opa(d, LV_OPA_COVER, 0);
        lv_obj_set_style_bg_color(d, i == id ? COLOR_ACCENT : COLOR_CARD, 0);
    }
    return scr;
}

lv_obj_t* ui_card(lv_obj_t* parent, int w, int h) {
    lv_obj_t* c = lv_obj_create(parent);
    lv_obj_set_size(c, w, h);
    lv_obj_set_style_bg_color(c, COLOR_CARD, 0);
    lv_obj_set_style_bg_opa(c, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(c, 0, 0);
    lv_obj_set_style_radius(c, 10, 0);
    lv_obj_set_style_pad_all(c, 8, 0);
    lv_obj_remove_flag(c, LV_OBJ_FLAG_SCROLLABLE);
    return c;
}
