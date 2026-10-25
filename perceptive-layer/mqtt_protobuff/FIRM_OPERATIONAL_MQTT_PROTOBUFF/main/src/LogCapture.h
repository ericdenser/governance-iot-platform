#pragma once

#include <stdint.h>

// Copia do ESP_LOG em buffer circular, sem tirar a saida da serial
namespace LogCapture {

constexpr int LINE_LEN  = 128;
constexpr int RING_SIZE = 48;

struct Line {
    uint32_t seq;
    char     level;          // 'E', 'W', 'I', 'D', 'V' ou ' '
    char     text[LINE_LEN];
};

void install();

// Linhas com seq > after, no maximo 'max' (as mais recentes)
int read(uint32_t after, Line* out, int max);

}  // namespace LogCapture
