#include <stdio.h>
#include <time.h>
#include "ui_common.h"
#include "SensorCache.h"

#define SPARK_POINTS 30   // x 10 s = 5 min

struct Metric {
    lv_obj_t*          value;
    lv_obj_t*          unit;
    lv_obj_t*          sub;
    lv_obj_t*          chart;
    lv_chart_series_t* series;
    int32_t            min_span; // amplitude minima do eixo Y, evita ampliar ruido
};

static Metric    s_temp, s_hum, s_press, s_air;
static lv_obj_t* s_air_dot  = nullptr;
static lv_obj_t* s_clock    = nullptr;

static lv_color_t air_color(AirLevel lvl) {
    switch (lvl) {
        case AirLevel::GOOD:     return COLOR_OK;
        case AirLevel::MODERATE: return COLOR_WARN;
        case AirLevel::POOR:     return COLOR_TEMP;
        case AirLevel::BAD:      return COLOR_ERR;
        default:                 return COLOR_MUTED;
    }
}

static lv_obj_t* make_card(lv_obj_t* scr, int x, int y, int w, int h,
                           lv_color_t accent, const char* title, lv_obj_t** dot_out) {
    lv_obj_t* card = ui_card(scr, w, h);
    lv_obj_set_pos(card, x, y);

    lv_obj_t* dot = lv_obj_create(card);
    lv_obj_remove_style_all(dot);
    lv_obj_set_size(dot, 8, 8);
    lv_obj_set_style_radius(dot, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_opa(dot, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(dot, accent, 0);
    lv_obj_set_pos(dot, 0, 4);
    if (dot_out) *dot_out = dot;

    lv_obj_t* t = ui_label(card, &lv_font_montserrat_12, COLOR_MUTED, title);
    lv_obj_set_pos(t, 13, 0);
    return card;
}

static void make_value(Metric& m, lv_obj_t* card, const lv_font_t* font, const char* unit) {
    m.value = ui_label(card, font, COLOR_TEXT, UI_NO_DATA);
    lv_obj_set_pos(m.value, 0, 18);
    m.unit = ui_label(card, &lv_font_montserrat_12, COLOR_MUTED, unit);
    lv_obj_align_to(m.unit, m.value, LV_ALIGN_OUT_RIGHT_BOTTOM, 3, -5);
}

static void make_spark(Metric& m, lv_obj_t* card, lv_color_t color, int32_t min_span) {
    m.min_span = min_span;
    m.chart = lv_chart_create(card);
    lv_obj_set_size(m.chart, lv_pct(100), 36);
    lv_obj_align(m.chart, LV_ALIGN_BOTTOM_MID, 0, 0);
    lv_chart_set_type(m.chart, LV_CHART_TYPE_LINE);
    lv_chart_set_point_count(m.chart, SPARK_POINTS);
    lv_chart_set_update_mode(m.chart, LV_CHART_UPDATE_MODE_SHIFT);
    lv_chart_set_div_line_count(m.chart, 0, 0);
    lv_obj_set_style_bg_opa(m.chart, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(m.chart, 0, 0);
    lv_obj_set_style_pad_all(m.chart, 2, 0);
    lv_obj_set_style_line_width(m.chart, 2, LV_PART_ITEMS);
    lv_obj_set_style_size(m.chart, 0, 0, LV_PART_INDICATOR);
    m.series = lv_chart_add_series(m.chart, color, LV_CHART_AXIS_PRIMARY_Y);
    lv_chart_set_all_values(m.chart, m.series, LV_CHART_POINT_NONE);
}

// Valores x10 (chart so aceita inteiro)
static void push_point(Metric& m, bool valid, float v) {
    lv_chart_set_next_value(m.chart, m.series, valid ? (int32_t)(v * 10.0f) : LV_CHART_POINT_NONE);

    int32_t* ys = lv_chart_get_series_y_array(m.chart, m.series);
    int32_t lo = INT32_MAX, hi = INT32_MIN;
    for (int i = 0; i < SPARK_POINTS; i++) {
        if (ys[i] == LV_CHART_POINT_NONE) continue;
        if (ys[i] < lo) lo = ys[i];
        if (ys[i] > hi) hi = ys[i];
    }
    if (lo == INT32_MAX) return;
    int32_t span = hi - lo;
    if (span < m.min_span) {
        int32_t mid = (hi + lo) / 2;
        lo = mid - m.min_span / 2;
        hi = mid + m.min_span / 2;
    } else {
        lo -= span / 8;
        hi += span / 8;
    }
    lv_chart_set_axis_range(m.chart, LV_CHART_AXIS_PRIMARY_Y, lo, hi);
}

static void set_value(Metric& m, bool valid, const char* fmt, float v) {
    char buf[16];
    if (valid) snprintf(buf, sizeof(buf), fmt, v);
    ui_label_update(m.value, valid ? buf : UI_NO_DATA);
    lv_obj_align_to(m.unit, m.value, LV_ALIGN_OUT_RIGHT_BOTTOM, 3, -5);
}

lv_obj_t* ui_dashboard_create(int w, int h) {
    lv_obj_t* scr = ui_screen_create(SCREEN_DASHBOARD, false);

    lv_obj_t* title = ui_label(scr, &lv_font_montserrat_16, COLOR_TEXT, LV_SYMBOL_HOME "  AMBIENTE");
    lv_obj_align(title, LV_ALIGN_TOP_LEFT, 12, 10);
    s_clock = ui_label(scr, &lv_font_montserrat_16, COLOR_MUTED, "--:--");
    lv_obj_align(s_clock, LV_ALIGN_TOP_RIGHT, -12, 10);

    const int margin = 12, gap = 8, top = 40, footer = 30;
    const int cw = (w - 2 * margin - gap) / 2;
    const int ch = (h - top - footer - gap) / 2;
    const int x0 = margin, x1 = margin + cw + gap;
    const int y0 = top,    y1 = top + ch + gap;

    lv_obj_t* c;
    c = make_card(scr, x0, y0, cw, ch, COLOR_TEMP, "Temperatura", nullptr);
    make_value(s_temp, c, &lv_font_montserrat_28, "\xC2\xB0""C");
    make_spark(s_temp, c, COLOR_TEMP, 10);

    c = make_card(scr, x1, y0, cw, ch, COLOR_HUM, "Umidade", nullptr);
    make_value(s_hum, c, &lv_font_montserrat_28, "%");
    make_spark(s_hum, c, COLOR_HUM, 50);

    c = make_card(scr, x0, y1, cw, ch, COLOR_PRESS, "Pressao", nullptr);
    make_value(s_press, c, &lv_font_montserrat_24, "hPa");
    make_spark(s_press, c, COLOR_PRESS, 20);

    c = make_card(scr, x1, y1, cw, ch, COLOR_MUTED, "Qualidade ar", &s_air_dot);
    make_value(s_air, c, &lv_font_montserrat_24, "");
    s_air.sub = ui_label(c, &lv_font_montserrat_12, COLOR_MUTED, "");
    lv_obj_set_pos(s_air.sub, 0, 46);
    make_spark(s_air, c, COLOR_OK, 1000);

    lv_obj_t* foot = ui_label(scr, &lv_font_montserrat_12, COLOR_MUTED, "graficos: ultimos 5 min");
    lv_obj_align(foot, LV_ALIGN_BOTTOM_MID, 0, -14);

    return scr;
}

void ui_dashboard_tick() {
    SensorSnapshot s = SensorCache::get();

    set_value(s_temp,  s.env_valid, "%.1f", s.temp_c);
    set_value(s_hum,   s.env_valid && s.hum_valid, "%.0f", s.hum_pct);
    set_value(s_press, s.env_valid, "%.0f", s.press_hpa);

    lv_color_t ac = air_color(s.air_level);
    lv_obj_set_style_bg_color(s_air_dot, ac, 0);
    lv_obj_set_style_text_color(s_air.value, s.air_valid ? ac : COLOR_TEXT, 0);
    lv_chart_set_series_color(s_air.chart, s_air.series, s.air_valid ? ac : COLOR_MUTED);

    char sub[32];
    if (s.air_valid) {
        ui_label_update(s_air.value, Mq135Manager::levelText(s.air_level));
        snprintf(sub, sizeof(sub), "~%.0f ppm CO2", s.co2_ppm);
    } else if (s.air_warming) {
        ui_label_update(s_air.value, UI_NO_DATA);
        snprintf(sub, sizeof(sub), "aquecendo %d:%02d", s.air_warmup_left_s / 60, s.air_warmup_left_s % 60);
    } else {
        ui_label_update(s_air.value, UI_NO_DATA);
        sub[0] = '\0';
    }
    ui_label_update(s_air.sub, sub);

    struct tm tmv;
    if (ui_local_time(&tmv)) {
        char clk[8];
        strftime(clk, sizeof(clk), "%H:%M", &tmv);
        ui_label_update(s_clock, clk);
    } else {
        ui_label_update(s_clock, "--:--");
    }
}

void ui_dashboard_sample() {
    SensorSnapshot s = SensorCache::get();
    push_point(s_temp,  s.env_valid, s.temp_c);
    push_point(s_hum,   s.env_valid && s.hum_valid, s.hum_pct);
    push_point(s_press, s.env_valid, s.press_hpa);
    push_point(s_air,   s.air_valid, s.co2_ppm);
}
