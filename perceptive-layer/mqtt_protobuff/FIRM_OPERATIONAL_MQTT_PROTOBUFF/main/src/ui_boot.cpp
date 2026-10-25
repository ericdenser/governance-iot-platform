#include <string.h>
#include "ui_common.h"

struct BootStep {
    const char* label;
    const char* states[5];
};

static const BootStep STEPS[] = {
    {"Memoria (NVS)",       {"NVS_INIT"}},
    {"WiFi",                {"WIFI_CONNECTING", "WIFI_AP_MODE"}},
    {"Relogio",             {"TIME_SYNC"}},
    {"Broker MQTT (mTLS)",  {"MQTT_INIT", "MQTT_WAITING_CONNECT"}},
    {"Auditoria de boot",   {"BOOT_AUDIT", "FIRMWARE_ROLLBACK", "OTA_SUCCESSFUL", "COMMAND_COMPLETE"}},
    {"Sensores",            {"SENSORS_INIT"}},
    {"Operacional",         {"OPERATIONAL"}},
};
static constexpr int STEP_COUNT = sizeof(STEPS) / sizeof(STEPS[0]);

struct StateText {
    const char* state;
    const char* text;
    bool        warn;
};

static const StateText STATE_TEXTS[] = {
    {"NVS_INIT",             "Lendo configuracao da flash", false},
    {"WIFI_AP_MODE",         "Aguardando configuracao WiFi", true},
    {"WIFI_CONNECTING",      "Conectando a rede WiFi",      false},
    {"TIME_SYNC",            "Sincronizando relogio",       false},
    {"MQTT_INIT",            "Iniciando cliente MQTT",      false},
    {"MQTT_WAITING_CONNECT", "Aguardando o broker",         false},
    {"BOOT_AUDIT",           "Verificando firmware",        false},
    {"FIRMWARE_ROLLBACK",    "Rollback de firmware",        true},
    {"OTA_SUCCESSFUL",       "Atualizacao aplicada",        false},
    {"COMMAND_COMPLETE",     "Comando concluido",           false},
    {"SENSORS_INIT",         "Detectando sensores",         false},
    {"OPERATIONAL",          "Enviando telemetria",         false},
    {"HTTP_INIT",            "Preparando requisicao HTTP",  false},
    {"HTTP_REQUEST",         "Requisicao HTTP em curso",    false},
    {"WAITING_RESPONSE",     "Aguardando resposta",         false},
    {"OTA_FOUND",            "Atualizacao encontrada",      true},
    {"OTA_DOWNLOADING",      "Baixando firmware",           true},
    {"REBOOTING",            "Reiniciando",                 true},
    {"CRITICAL_BATTERY",     "Bateria critica",             true},
    {"ERROR",                "Tratando erro",               true},
};

enum class RowState { PENDING, ACTIVE, DONE, FAIL };

struct Row {
    lv_obj_t* icon;
    lv_obj_t* text;
};

static lv_obj_t*  s_spinner      = nullptr;
static lv_obj_t*  s_online_icon  = nullptr;
static lv_obj_t*  s_bar          = nullptr;
static lv_obj_t*  s_card         = nullptr;
static lv_obj_t*  s_card_title   = nullptr;
static lv_obj_t*  s_card_body    = nullptr;
static Row        s_rows[STEP_COUNT];
static int        s_active_step  = -1;
static bool       s_error_shown  = false;

static int find_step(const char* state) {
    for (int i = 0; i < STEP_COUNT; i++) {
        for (const char* s : STEPS[i].states) {
            if (s == nullptr) break;
            if (strcmp(s, state) == 0) return i;
        }
    }
    return -1;
}

static const StateText* find_text(const char* state) {
    for (const auto& t : STATE_TEXTS) {
        if (strcmp(t.state, state) == 0) return &t;
    }
    return nullptr;
}

static void set_row(int i, RowState st) {
    const char* icon;
    lv_color_t  icon_color;
    lv_color_t  text_color;
    switch (st) {
        case RowState::ACTIVE: icon = LV_SYMBOL_REFRESH; icon_color = COLOR_ACCENT; text_color = COLOR_TEXT;  break;
        case RowState::DONE:   icon = LV_SYMBOL_OK;      icon_color = COLOR_OK;     text_color = COLOR_TEXT;  break;
        case RowState::FAIL:   icon = LV_SYMBOL_CLOSE;   icon_color = COLOR_ERR;    text_color = COLOR_ERR;   break;
        case RowState::PENDING:
        default:               icon = LV_SYMBOL_MINUS;   icon_color = COLOR_MUTED;  text_color = COLOR_MUTED; break;
    }
    lv_label_set_text(s_rows[i].icon, icon);
    lv_obj_set_style_text_color(s_rows[i].icon, icon_color, 0);
    lv_obj_set_style_text_color(s_rows[i].text, text_color, 0);
}

static void set_card(const char* title, const char* body, lv_color_t bg, lv_color_t title_color) {
    lv_obj_set_style_bg_color(s_card, bg, 0);
    lv_obj_set_style_text_color(s_card_title, title_color, 0);
    lv_label_set_text(s_card_title, title);
    lv_label_set_text(s_card_body, body ? body : "");
}

lv_obj_t* ui_boot_create(int width, int height) {
    lv_obj_t* scr = ui_screen_create(SCREEN_BOOT, true);
    (void)height;

    lv_obj_t* title = ui_label(scr, &lv_font_montserrat_16, COLOR_TEXT, "GOVERNANCE IoT");
    lv_obj_align(title, LV_ALIGN_TOP_LEFT, 12, 12);

    s_spinner = lv_spinner_create(scr);
    lv_spinner_set_anim_params(s_spinner, 1000, 270);
    lv_obj_set_size(s_spinner, 22, 22);
    lv_obj_align(s_spinner, LV_ALIGN_TOP_RIGHT, -12, 10);
    lv_obj_set_style_arc_width(s_spinner, 3, LV_PART_MAIN);
    lv_obj_set_style_arc_color(s_spinner, COLOR_CARD, LV_PART_MAIN);
    lv_obj_set_style_arc_width(s_spinner, 3, LV_PART_INDICATOR);
    lv_obj_set_style_arc_color(s_spinner, COLOR_ACCENT, LV_PART_INDICATOR);

    s_online_icon = ui_label(scr, &lv_font_montserrat_16, COLOR_OK, LV_SYMBOL_WIFI);
    lv_obj_align(s_online_icon, LV_ALIGN_TOP_RIGHT, -12, 12);
    lv_obj_add_flag(s_online_icon, LV_OBJ_FLAG_HIDDEN);

    s_bar = lv_bar_create(scr);
    lv_obj_set_size(s_bar, width - 24, 6);
    lv_obj_align(s_bar, LV_ALIGN_TOP_MID, 0, 42);
    lv_bar_set_range(s_bar, 0, 100);
    lv_obj_set_style_bg_color(s_bar, COLOR_CARD, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(s_bar, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(s_bar, COLOR_ACCENT, LV_PART_INDICATOR);
    lv_obj_set_style_radius(s_bar, 3, LV_PART_MAIN);
    lv_obj_set_style_radius(s_bar, 3, LV_PART_INDICATOR);

    const int row_y0 = 58;
    const int row_h  = 27;
    for (int i = 0; i < STEP_COUNT; i++) {
        s_rows[i].icon = ui_label(scr, &lv_font_montserrat_14, COLOR_MUTED, LV_SYMBOL_MINUS);
        lv_obj_set_pos(s_rows[i].icon, 16, row_y0 + i * row_h);
        s_rows[i].text = ui_label(scr, &lv_font_montserrat_14, COLOR_MUTED, STEPS[i].label);
        lv_obj_set_pos(s_rows[i].text, 42, row_y0 + i * row_h);
    }

    s_card = ui_card(scr, width - 16, 54);
    lv_obj_set_style_radius(s_card, 8, 0);
    lv_obj_align(s_card, LV_ALIGN_BOTTOM_MID, 0, -16);

    s_card_title = ui_label(s_card, &lv_font_montserrat_12, COLOR_MUTED, "BOOT");
    lv_obj_align(s_card_title, LV_ALIGN_TOP_LEFT, 0, 0);

    s_card_body = ui_label(s_card, &lv_font_montserrat_14, COLOR_TEXT, "Ligando display");
    lv_obj_set_width(s_card_body, width - 32);
    lv_label_set_long_mode(s_card_body, LV_LABEL_LONG_MODE_SCROLL_CIRCULAR);
    lv_obj_align(s_card_body, LV_ALIGN_TOP_LEFT, 0, 18);

    return scr;
}

bool ui_boot_on_state(const char* state, const char* detail, bool is_error) {
    if (is_error) {
        if (s_active_step >= 0) set_row(s_active_step, RowState::FAIL);
        set_card(state, detail, COLOR_ERR_CARD, COLOR_ERR);
        s_error_shown = true;
        return false;
    }

    const StateText* txt = find_text(state);
    const char* body = txt ? txt->text : state;
    int step = find_step(state);

    if (step >= 0) {
        bool online = (step == STEP_COUNT - 1);
        for (int i = 0; i < STEP_COUNT; i++) {
            if (i < step || (i == step && online)) set_row(i, RowState::DONE);
            else if (i == step)                    set_row(i, RowState::ACTIVE);
            else                                   set_row(i, RowState::PENDING);
        }
        s_active_step = step;
        lv_bar_set_value(s_bar, step * 100 / (STEP_COUNT - 1), LV_ANIM_ON);
        lv_obj_set_style_bg_color(s_bar, online ? COLOR_OK : COLOR_ACCENT, LV_PART_INDICATOR);

        if (online) {
            lv_obj_add_flag(s_spinner, LV_OBJ_FLAG_HIDDEN);
            lv_obj_remove_flag(s_online_icon, LV_OBJ_FLAG_HIDDEN);
        } else {
            lv_obj_remove_flag(s_spinner, LV_OBJ_FLAG_HIDDEN);
            lv_obj_add_flag(s_online_icon, LV_OBJ_FLAG_HIDDEN);
        }

        set_card(state, body, (txt && txt->warn) ? COLOR_WARN_CARD : COLOR_CARD,
                 online ? COLOR_OK : COLOR_MUTED);
        s_error_shown = false;
        return online;
    }

    if (!(s_error_shown && strcmp(state, "ERROR") == 0)) {
        // ERROR logo apos um setError mantem o card vermelho
        bool warn = txt && txt->warn;
        set_card(state, body, warn ? COLOR_WARN_CARD : COLOR_CARD, warn ? COLOR_WARN : COLOR_MUTED);
        s_error_shown = false;
    }
    return false;
}
