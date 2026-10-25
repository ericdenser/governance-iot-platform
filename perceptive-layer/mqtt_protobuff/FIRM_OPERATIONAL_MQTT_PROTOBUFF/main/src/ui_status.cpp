#include <stdio.h>
#include <string.h>
#include <time.h>
#include "ui_common.h"
#include "SensorCache.h"
#include "nvs.h"
#include "esp_mac.h"
#include "esp_wifi.h"
#include "esp_netif.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "esp_heap_caps.h"

enum RowId {
    ROW_FW, ROW_DEVICE, ROW_MAC, ROW_SSID, ROW_RSSI, ROW_IP, ROW_CHIP,
    ROW_BAT, ROW_HEAP, ROW_UPTIME, ROW_RESET, ROW_SENSORS, ROW_CLOCK, ROW_COUNT
};

static const char* ROW_KEYS[ROW_COUNT] = {
    "Firmware", "Device ID", "MAC", "SSID", "Sinal", "IP", "Chip",
    "Bateria", "Heap livre", "Uptime", "Ult. reset", "Sensores", "Hora",
};

static lv_obj_t* s_values[ROW_COUNT];
static lv_obj_t* s_state       = nullptr;
static lv_obj_t* s_state_card  = nullptr;
static lv_obj_t* s_state_detail= nullptr;

static char s_fw[32]          = "";
static char s_device_id[64]   = "";
static char s_sensors[96]     = "";
static char s_last_error[96]  = "";

static void set_row(RowId id, const char* text) {
    ui_label_update(s_values[id], (text && text[0]) ? text : UI_NO_DATA);
}

// Mesmas chaves lidas pelo governance_core no handle_nvs_init
static void load_nvs_once() {
    nvs_handle_t h;
    if (s_fw[0] == '\0' && nvs_open("main_store", NVS_READONLY, &h) == ESP_OK) {
        size_t sz = sizeof(s_fw);
        if (nvs_get_str(h, "fw_version", s_fw, &sz) != ESP_OK) s_fw[0] = '\0';
        nvs_close(h);
    }
    if (s_device_id[0] == '\0' && nvs_open("crypto_store", NVS_READONLY, &h) == ESP_OK) {
        size_t sz = sizeof(s_device_id);
        if (nvs_get_str(h, "device_id", s_device_id, &sz) != ESP_OK) s_device_id[0] = '\0';
        nvs_close(h);
    }
}

static const char* reset_reason_text(esp_reset_reason_t r) {
    switch (r) {
        case ESP_RST_POWERON:   return "Power-on";
        case ESP_RST_SW:        return "Software";
        case ESP_RST_PANIC:     return "Panic";
        case ESP_RST_INT_WDT:   return "WDT (int)";
        case ESP_RST_TASK_WDT:  return "WDT (task)";
        case ESP_RST_WDT:       return "WDT";
        case ESP_RST_DEEPSLEEP: return "Deep sleep";
        case ESP_RST_BROWNOUT:  return "Brownout";
        case ESP_RST_EXT:       return "Pino EN";
        case ESP_RST_USB:       return "USB";
        default:                return nullptr;
    }
}

static const char* rssi_quality(int rssi) {
    if (rssi >= -55) return "otimo";
    if (rssi >= -67) return "bom";
    if (rssi >= -75) return "fraco";
    return "ruim";
}

lv_obj_t* ui_status_create(int w, int h) {
    lv_obj_t* scr = ui_screen_create(SCREEN_STATUS, false);
    (void)h;

    lv_obj_t* title = ui_label(scr, &lv_font_montserrat_16, COLOR_TEXT, LV_SYMBOL_SETTINGS "  DISPOSITIVO");
    lv_obj_align(title, LV_ALIGN_TOP_LEFT, 12, 10);

    s_state_card = ui_card(scr, w - 24, 44);
    lv_obj_set_style_pad_all(s_state_card, 6, 0);
    lv_obj_align(s_state_card, LV_ALIGN_TOP_MID, 0, 36);
    s_state = ui_label(s_state_card, &lv_font_montserrat_16, COLOR_ACCENT, UI_NO_DATA);
    lv_obj_align(s_state, LV_ALIGN_TOP_LEFT, 2, 0);
    lv_obj_set_width(s_state, w - 40);
    lv_label_set_long_mode(s_state, LV_LABEL_LONG_MODE_DOTS);
    s_state_detail = ui_label(s_state_card, &lv_font_montserrat_12, COLOR_MUTED, "Sem erros desde o boot");
    lv_obj_set_width(s_state_detail, w - 40);
    lv_label_set_long_mode(s_state_detail, LV_LABEL_LONG_MODE_SCROLL_CIRCULAR);
    lv_obj_align(s_state_detail, LV_ALIGN_TOP_LEFT, 2, 18);

    const int y0 = 88, row_h = 16, key_x = 14, val_x = 86;
    for (int i = 0; i < ROW_COUNT; i++) {
        lv_obj_t* k = ui_label(scr, &lv_font_montserrat_12, COLOR_MUTED, ROW_KEYS[i]);
        lv_obj_set_pos(k, key_x, y0 + i * row_h);
        s_values[i] = ui_label(scr, &lv_font_montserrat_12, COLOR_TEXT, UI_NO_DATA);
        lv_obj_set_pos(s_values[i], val_x, y0 + i * row_h);
        lv_obj_set_width(s_values[i], w - val_x - 12);
        lv_label_set_long_mode(s_values[i], LV_LABEL_LONG_MODE_SCROLL_CIRCULAR);
    }
    return scr;
}

void ui_status_on_state(const char* state, const char* detail, bool is_error) {
    if (is_error) {
        snprintf(s_last_error, sizeof(s_last_error), "Ultimo erro: %s%s%s",
                 state, (detail && detail[0]) ? " - " : "", detail ? detail : "");
        lv_label_set_text(s_state, "ERROR");
        lv_obj_set_style_text_color(s_state, COLOR_ERR, 0);
        lv_obj_set_style_bg_color(s_state_card, COLOR_ERR_CARD, 0);
    } else {
        lv_color_t c = COLOR_ACCENT;
        lv_color_t bg = COLOR_CARD;
        if (strcmp(state, "OPERATIONAL") == 0) c = COLOR_OK;
        else if (strcmp(state, "ERROR") == 0) { c = COLOR_ERR; bg = COLOR_ERR_CARD; }
        else if (strncmp(state, "OTA", 3) == 0 || strcmp(state, "FIRMWARE_ROLLBACK") == 0 ||
                 strcmp(state, "REBOOTING") == 0 || strcmp(state, "CRITICAL_BATTERY") == 0) c = COLOR_WARN;
        lv_label_set_text(s_state, state);
        lv_obj_set_style_text_color(s_state, c, 0);
        lv_obj_set_style_bg_color(s_state_card, bg, 0);
    }
    ui_label_update(s_state_detail, s_last_error[0] ? s_last_error : "Sem erros desde o boot");
}

void ui_status_set_sensors(const char* csv) {
    snprintf(s_sensors, sizeof(s_sensors), "%s", csv ? csv : "");
    set_row(ROW_SENSORS, s_sensors);
}

void ui_status_tick() {
    char buf[64];
    load_nvs_once();

    if (s_fw[0]) { snprintf(buf, sizeof(buf), "v%s", s_fw); set_row(ROW_FW, buf); }
    else         { set_row(ROW_FW, nullptr); }
    set_row(ROW_DEVICE, s_device_id);

    uint8_t mac[6];
    if (esp_read_mac(mac, ESP_MAC_WIFI_STA) == ESP_OK) {
        snprintf(buf, sizeof(buf), "%02X:%02X:%02X:%02X:%02X:%02X",
                 mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
        set_row(ROW_MAC, buf);
    } else {
        set_row(ROW_MAC, nullptr);
    }

    wifi_ap_record_t ap;
    if (esp_wifi_sta_get_ap_info(&ap) == ESP_OK) {
        set_row(ROW_SSID, (const char*)ap.ssid);
        snprintf(buf, sizeof(buf), "%d dBm (%s)", ap.rssi, rssi_quality(ap.rssi));
        set_row(ROW_RSSI, buf);
    } else {
        set_row(ROW_SSID, nullptr);
        set_row(ROW_RSSI, nullptr);
    }

    esp_netif_t* netif = esp_netif_get_handle_from_ifkey("WIFI_STA_DEF");
    esp_netif_ip_info_t ip;
    if (netif && esp_netif_get_ip_info(netif, &ip) == ESP_OK && ip.ip.addr != 0) {
        snprintf(buf, sizeof(buf), IPSTR, IP2STR(&ip.ip));
        set_row(ROW_IP, buf);
    } else {
        set_row(ROW_IP, nullptr);
    }

    SensorSnapshot s = SensorCache::get();
    if (s.chip_valid) { snprintf(buf, sizeof(buf), "%.1f \xC2\xB0""C", s.chip_c); set_row(ROW_CHIP, buf); }
    else              { set_row(ROW_CHIP, nullptr); }
    if (s.bat_valid)  { snprintf(buf, sizeof(buf), "%.2f V", s.bat_mv / 1000.0f); set_row(ROW_BAT, buf); }
    else              { set_row(ROW_BAT, nullptr); }

    snprintf(buf, sizeof(buf), "%lu KB (min %lu)",
             (unsigned long)(esp_get_free_heap_size() / 1024),
             (unsigned long)(esp_get_minimum_free_heap_size() / 1024));
    set_row(ROW_HEAP, buf);

    uint32_t up = (uint32_t)(esp_timer_get_time() / 1000000);
    if (up >= 86400) snprintf(buf, sizeof(buf), "%lud %02lu:%02lu:%02lu", (unsigned long)(up / 86400),
                              (unsigned long)(up / 3600 % 24), (unsigned long)(up / 60 % 60), (unsigned long)(up % 60));
    else             snprintf(buf, sizeof(buf), "%02lu:%02lu:%02lu", (unsigned long)(up / 3600),
                              (unsigned long)(up / 60 % 60), (unsigned long)(up % 60));
    set_row(ROW_UPTIME, buf);

    set_row(ROW_RESET, reset_reason_text(esp_reset_reason()));
    set_row(ROW_SENSORS, s_sensors);

    struct tm tmv;
    if (ui_local_time(&tmv)) {
        strftime(buf, sizeof(buf), "%d/%m %H:%M:%S", &tmv);
        set_row(ROW_CLOCK, buf);
    } else {
        set_row(ROW_CLOCK, nullptr);
    }
}
