#include "LogCapture.h"

#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include "esp_log.h"
#include "freertos/FreeRTOS.h"

namespace LogCapture {

static Line         s_ring[RING_SIZE];
static uint32_t     s_seq  = 0;
static portMUX_TYPE s_lock = portMUX_INITIALIZER_UNLOCKED;
static vprintf_like_t s_prev = nullptr;

// Tira cores ANSI e quebras de linha
static void sanitize(const char* in, char* out, size_t out_len) {
    size_t o = 0;
    for (size_t i = 0; in[i] != '\0' && o + 1 < out_len; i++) {
        if (in[i] == '\033') {
            while (in[i] != '\0' && in[i] != 'm') i++;
            if (in[i] == '\0') break;
            continue;
        }
        if (in[i] == '\r' || in[i] == '\n') continue;
        out[o++] = in[i];
    }
    out[o] = '\0';
}

static void push(const char* raw) {
    char clean[LINE_LEN];
    sanitize(raw, clean, sizeof(clean));
    if (clean[0] == '\0') return;

    char level = ' ';
    if (strchr("EWIDV", clean[0]) && clean[1] == ' ' && clean[2] == '(') level = clean[0];

    portENTER_CRITICAL_SAFE(&s_lock);
    s_seq++;
    Line& l = s_ring[s_seq % RING_SIZE];
    l.seq   = s_seq;
    l.level = level;
    memcpy(l.text, clean, sizeof(l.text));
    portEXIT_CRITICAL_SAFE(&s_lock);
}

static int log_vprintf(const char* fmt, va_list args) {
    char buf[LINE_LEN + 16];
    va_list copy;
    va_copy(copy, args);
    vsnprintf(buf, sizeof(buf), fmt, copy);
    va_end(copy);
    push(buf);

    return s_prev ? s_prev(fmt, args) : vprintf(fmt, args);
}

void install() {
    if (s_prev != nullptr) return;
    s_prev = esp_log_set_vprintf(log_vprintf);
}

int read(uint32_t after, Line* out, int max) {
    portENTER_CRITICAL(&s_lock);
    uint32_t last  = s_seq;
    uint32_t first = (last >= (uint32_t)RING_SIZE) ? last - RING_SIZE + 1 : 1;
    if (after + 1 > first) first = after + 1;
    if (last >= first && last - first + 1 > (uint32_t)max) first = last - max + 1;

    int n = 0;
    for (uint32_t s = first; s <= last && last != 0; s++) {
        out[n++] = s_ring[s % RING_SIZE];
    }
    portEXIT_CRITICAL(&s_lock);
    return n;
}

}  // namespace LogCapture
